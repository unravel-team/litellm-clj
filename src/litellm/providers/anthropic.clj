(ns litellm.providers.anthropic
  "Anthropic provider implementation for LiteLLM"
  (:require [litellm.providers.core :as core]
            [litellm.streaming :as streaming]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [go >!]]
            [com.climate.claypoole :as cp]))

;; ============================================================================
;; Message Transformations
;; ============================================================================

(defn transform-messages
  "Transform messages to Anthropic format"
  [messages]
  ;; Anthropic uses a different format than OpenAI
  ;; It expects a system prompt separately and a single conversation string
  (let [system-message (first (filter #(= :system (:role %)) messages))
        other-messages (remove #(= :system (:role %)) messages)]
    
    {:system (when system-message (:content system-message))
     :messages (map (fn [msg]
                      {:role (case (:role msg)
                               :user "user"
                               :assistant "assistant"
                               :tool "assistant" ;; Anthropic doesn't have tool role, map to assistant
                               (name (:role msg)))
                       :content (:content msg)})
                    other-messages)}))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-message
  "Transform Anthropic message to standard format"
  [message]
  {:role (keyword (:role message))
   :content (:content message)})

(defn transform-choice
  "Transform Anthropic response to standard choice format"
  [response index]
  {:index index
   :message {:role :assistant
             :content (get-in response [:content 0 :text])}
   :finish-reason (keyword (:stop_reason response))})

(defn transform-usage
  "Transform Anthropic usage to standard format"
  [usage]
  (when usage
    {:prompt-tokens (:input_tokens usage)
     :completion-tokens (:output_tokens usage)
     :total-tokens (+ (or (:input_tokens usage) 0)
                      (or (:output_tokens usage) 0))}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle Anthropic API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-info (get body :error {})]
    
    (case status
      401 (throw (ex-info "Authentication failed" 
                          {:type :authentication-error
                           :provider "anthropic"}))
      429 (throw (ex-info "Rate limit exceeded" 
                          {:type :rate-limit-error
                           :provider "anthropic"
                           :retry-after (get-in response [:headers "retry-after"])}))
      404 (throw (ex-info "Model not found" 
                          {:type :model-not-found-error
                           :provider "anthropic"
                           :model (:model body)}))
      (throw (ex-info (or (:message error-info) "Unknown error")
                      {:type :provider-error
                       :provider "anthropic"
                       :status status
                       :code (:type error-info)
                       :data error-info})))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for Anthropic models (in USD)"
  {"claude-3-opus" {:input 0.00001500 :output 0.00007500}
   "claude-3-sonnet" {:input 0.00000300 :output 0.00001500}
   "claude-3-haiku" {:input 0.00000025 :output 0.00000125}
   "claude-2.1" {:input 0.00000800 :output 0.00002400}
   "claude-2.0" {:input 0.00001100 :output 0.00003300}
   "claude-instant-1.2" {:input 0.00000163 :output 0.00000551}})

(def default-model-mapping
  "Default model name mappings"
  {"claude-3-opus" "claude-3-opus-20240229"
   "claude-3-sonnet" "claude-3-sonnet-20240229"
   "claude-3-haiku" "claude-3-haiku-20240307"
   "claude-2.1" "claude-2.1"
   "claude-2.0" "claude-2.0"
   "claude-instant-1.2" "claude-instant-1.2"})

;; ============================================================================
;; Anthropic Provider Multimethod Implementations
;; ============================================================================

(defmethod core/transform-request :anthropic
  [_ request config]
  (let [model (core/extract-model-name (:model request))
        mapped-model (get (:model-mapping config default-model-mapping) model model)
        messages-data (transform-messages (:messages request))
        transformed {:model mapped-model
                     :max_tokens (:max-tokens request 1024)
                     :temperature (:temperature request 0.7)
                     :top_p (:top-p request 1.0)
                     :stream (:stream request false)}]
    
    ;; Add system prompt if present
    (cond-> transformed
      (:system messages-data) (assoc :system (:system messages-data))
      (:messages messages-data) (assoc :messages (:messages messages-data)))))

(defmethod core/make-request :anthropic
  [_ transformed-request thread-pools telemetry config]
  (let [url (str (:api-base config "https://api.anthropic.com") "/v1/messages")]
    (cp/future (:api-calls thread-pools)
      (let [start-time (System/currentTimeMillis)
            response (http/post url
                                {:headers {"x-api-key" (:api-key config)
                                           "anthropic-version" "2023-06-01"
                                           "Content-Type" "application/json"
                                           "User-Agent" "litellm-clj/1.0.0"}
                                 :body (json/encode transformed-request)
                                 :timeout (:timeout config 30000)
                                 :as :json})
            duration (- (System/currentTimeMillis) start-time)]
        
        ;; Handle errors
        (when (>= (:status response) 400)
          (handle-error-response :anthropic response))
        
        response))))

(defmethod core/transform-response :anthropic
  [_ response]
  (let [body (:body response)]
    {:id (:id body)
     :object "chat.completion"
     :created (quot (System/currentTimeMillis) 1000)
     :model (:model body)
     :choices [(transform-choice body 0)]
     :usage (transform-usage (:usage body))}))

(defmethod core/supports-streaming? :anthropic [_] true)

(defmethod core/supports-function-calling? :anthropic [_] false)

(defmethod core/get-rate-limits :anthropic [_]
  {:requests-per-minute 240
   :tokens-per-minute 60000})

(defmethod core/health-check :anthropic
  [_ thread-pools config]
  (cp/future (:health-checks thread-pools)
    (try
      (let [response (http/get (str (:api-base config "https://api.anthropic.com") "/v1/models")
                              {:headers {"x-api-key" (:api-key config)
                                         "anthropic-version" "2023-06-01"}
                               :timeout 5000})]
        (= 200 (:status response)))
      (catch Exception e
        (log/warn "Anthropic health check failed" {:error (.getMessage e)})
        false))))

(defmethod core/get-cost-per-token :anthropic
  [_ model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defmethod core/transform-streaming-chunk :anthropic
  [_ chunk]
  (let [event-type (:type chunk)]
    (case event-type
      "content_block_delta" {:id (:message_id chunk)
                             :object "chat.completion.chunk"
                             :created (quot (System/currentTimeMillis) 1000)
                             :model (:model chunk)
                             :choices [{:index 0
                                       :delta {:role :assistant
                                              :content (get-in chunk [:delta :text])}
                                       :finish-reason nil}]}
      "message_stop" {:id (:message_id chunk)
                     :object "chat.completion.chunk"
                     :created (quot (System/currentTimeMillis) 1000)
                     :model (:model chunk)
                     :choices [{:index 0
                               :delta {}
                               :finish-reason :stop}]}
      nil)))

(defmethod core/make-streaming-request :anthropic
  [_ transformed-request thread-pools config]
  (let [url (str (:api-base config "https://api.anthropic.com") "/v1/messages")
        output-ch (streaming/create-stream-channel)]
    ;; Use thread instead of go for blocking I/O
    (async/thread
      (try
        (log/debug "Making Anthropic streaming request" {:url url :request transformed-request})
        (let [response (http/post url
                                  {:headers {"x-api-key" (:api-key config)
                                             "anthropic-version" "2023-06-01"
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode transformed-request)
                                   :timeout (:timeout config 30000)
                                   :as :stream})]
          
          (log/debug "Received response" {:status (:status response) :headers (:headers response)})
          
          ;; Check if response is valid
          (cond
            (nil? response)
            (do
              (log/error "Received nil response from Anthropic API")
              (async/>!! output-ch (streaming/stream-error "anthropic" "Received nil response"))
              (streaming/close-stream! output-ch))
            
            (and (:status response) (>= (:status response) 400))
            (do
              (log/error "HTTP error from Anthropic" {:status (:status response)})
              (async/>!! output-ch (streaming/stream-error "anthropic" 
                                                           (str "HTTP " (:status response))
                                                           :status (:status response)))
              (streaming/close-stream! output-ch))
            
            ;; Process streaming response (200 status)
            :else
            (let [body (:body response)]
              (if (nil? body)
                (do
                  (log/error "Response body is nil")
                  (async/>!! output-ch (streaming/stream-error "anthropic" "Response body is nil"))
                  (streaming/close-stream! output-ch))
                
                (let [reader (java.io.BufferedReader. 
                              (java.io.InputStreamReader. body "UTF-8"))]
                  (try
                    (log/debug "Starting to read SSE stream")
                    (loop [chunk-count 0]
                      (if-let [line (.readLine reader)]
                        (do
                          (when (str/starts-with? line "data: ")
                            (let [data (subs line 6)]
                              (when-not (= data "[DONE]")
                                (try
                                  (let [parsed (json/decode data true)
                                        transformed (core/transform-streaming-chunk :anthropic parsed)]
                                    (when transformed
                                      (async/>!! output-ch transformed)
                                      (log/debug "Sent chunk" {:chunk-number (inc chunk-count)})))
                                  (catch Exception e
                                    (log/warn "Failed to parse SSE line" {:line line :error (or (.getMessage e) (str e))}))))))
                          (recur (inc chunk-count)))
                        (log/debug "Stream ended" {:total-chunks chunk-count})))
                    (finally
                      (.close reader)
                      (streaming/close-stream! output-ch)
                      (log/debug "Closed reader and output channel"))))))))
        
        (catch Exception e
          (log/error "Error in streaming request" 
                     {:error (or (.getMessage e) (str e))
                      :error-class (class e)
                      :stack-trace (take 5 (.getStackTrace e))})
          (async/>!! output-ch (streaming/stream-error "anthropic" (or (.getMessage e) (str "Exception: " (class e)))))
          (streaming/close-stream! output-ch))))
    
    output-ch))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available Anthropic models"
  [provider]
  (try
    (let [response (http/get (str (:api-base provider) "/v1/models")
                            {:headers {"x-api-key" (:api-key provider)
                                       "anthropic-version" "2023-06-01"}
                             :as :json})]
      (if (= 200 (:status response))
        (map :id (get-in response [:body :data]))
        (throw (ex-info "Failed to list models" {:status (:status response)}))))
    (catch Exception e
      (log/error "Error listing Anthropic models" e)
      [])))

(defn validate-api-key
  "Validate Anthropic API key"
  [api-key]
  (try
    (let [response (http/get "https://api.anthropic.com/v1/models"
                            {:headers {"x-api-key" api-key
                                       "anthropic-version" "2023-06-01"}
                             :timeout 5000})]
      (= 200 (:status response)))
    (catch Exception e
      (log/debug "API key validation failed" {:error (.getMessage e)})
      false)))

;; ============================================================================
;; Provider Testing
;; ============================================================================

(defn test-anthropic-connection
  "Test Anthropic connection with a simple request"
  [provider thread-pools telemetry]
  (let [test-request {:model "claude-3-haiku"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (core/transform-request provider test-request)
            response-future (core/make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (core/transform-response provider response)]
        {:success true
         :provider "anthropic"
         :model "claude-3-haiku"
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "anthropic"
         :error (.getMessage e)
         :error-type (type e)}))))
