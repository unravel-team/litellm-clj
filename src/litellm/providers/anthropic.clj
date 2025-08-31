(ns litellm.providers.anthropic
  "Anthropic provider implementation for LiteLLM"
  (:require [litellm.providers.core :as core]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
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
;; Anthropic Provider Record
;; ============================================================================

(defrecord AnthropicProvider [api-key api-base model-mapping rate-limits cost-map timeout]
  core/LLMProvider
  
  (provider-name [_] "anthropic")
  
  (transform-request [provider request]
    (let [model (core/extract-model-name (:model request))
          mapped-model (get (:model-mapping provider) model model)
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
  
  (make-request [provider transformed-request thread-pools telemetry]
    (let [url (str (:api-base provider) "/v1/messages")]
      (cp/future (:api-calls thread-pools)
        (let [start-time (System/currentTimeMillis)
              response (http/post url
                                  {:headers {"x-api-key" (:api-key provider)
                                             "anthropic-version" "2023-06-01"
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode transformed-request)
                                   :timeout (:timeout provider 30000)
                                   :as :json})
              duration (- (System/currentTimeMillis) start-time)]
          
          ;; Handle errors
          (when (>= (:status response) 400)
            (handle-error-response provider response))
          
          response))))
  
  (transform-response [provider response]
    (let [body (:body response)]
      {:id (:id body)
       :object "chat.completion"
       :created (quot (System/currentTimeMillis) 1000)
       :model (:model body)
       :choices [(transform-choice body 0)]
       :usage (transform-usage (:usage body))}))
  
  (supports-streaming? [_] true)
  
  (supports-function-calling? [_] false) ;; Anthropic doesn't support function calling yet
  
  (get-rate-limits [provider]
    (:rate-limits provider {:requests-per-minute 240
                           :tokens-per-minute 60000}))
  
  (health-check [provider thread-pools]
    (cp/future (:health-checks thread-pools)
      (try
        (let [response (http/get (str (:api-base provider) "/v1/models")
                                {:headers {"x-api-key" (:api-key provider)
                                           "anthropic-version" "2023-06-01"}
                                 :timeout 5000})]
          (= 200 (:status response)))
        (catch Exception e
          (log/warn "Anthropic health check failed" {:error (.getMessage e)})
          false))))
  
  (get-cost-per-token [provider model]
    (get (:cost-map provider) model {:input 0.0 :output 0.0})))

;; ============================================================================
;; Provider Factory
;; ============================================================================

(defn create-anthropic-provider
  "Create Anthropic provider instance"
  [config]
  (map->AnthropicProvider
    {:api-key (:api-key config)
     :api-base (:api-base config "https://api.anthropic.com")
     :model-mapping (merge default-model-mapping (:model-mapping config {}))
     :rate-limits (:rate-limits config {:requests-per-minute 240
                                       :tokens-per-minute 60000})
     :cost-map (merge default-cost-map (:cost-map config {}))
     :timeout (:timeout config 30000)}))

;; ============================================================================
;; Provider Registration
;; ============================================================================

(defn register-anthropic-provider!
  "Register Anthropic provider in the global registry"
  []
  (core/register-provider! "anthropic" create-anthropic-provider))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defn parse-sse-line
  "Parse a Server-Sent Events line"
  [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when-not (= data "[DONE]")
        (try
          (json/decode data true)
          (catch Exception e
            (log/debug "Failed to parse SSE line" {:line line :error (.getMessage e)})
            nil))))))

(defn transform-streaming-chunk
  "Transform Anthropic streaming chunk to standard format"
  [chunk]
  {:id (:id chunk)
   :object "chat.completion.chunk"
   :created (quot (System/currentTimeMillis) 1000)
   :model (:model chunk)
   :choices [{:index 0
             :delta {:role :assistant
                    :content (get-in chunk [:delta :text])}
             :finish-reason (when (:stop_reason chunk)
                             (keyword (:stop_reason chunk)))}]})

(defn handle-streaming-response
  "Handle streaming response from Anthropic"
  [response callback]
  (let [body (:body response)]
    (doseq [line (str/split-lines body)]
      (when-let [parsed (parse-sse-line line)]
        (let [transformed (transform-streaming-chunk parsed)]
          (callback transformed))))))

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

;; Auto-register the provider when namespace is loaded
(register-anthropic-provider!)
