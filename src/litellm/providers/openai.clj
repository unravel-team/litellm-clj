(ns litellm.providers.openai
  "OpenAI provider implementation for LiteLLM"
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
  "Transform messages to OpenAI format"
  [messages]
  (map (fn [msg]
         (let [base {:role (name (:role msg))
                    :content (:content msg)}]
           (cond-> base
             (:name msg) (assoc :name (:name msg))
             (:tool-call-id msg) (assoc :tool_call_id (:tool-call-id msg)))))
       messages))

(defn transform-tools
  "Transform tools to OpenAI format"
  [tools]
  (when tools
    (map (fn [tool]
           {:type (:tool-type tool "function")
            :function (select-keys (:function tool) [:name :description :parameters])})
         tools)))

(defn transform-tool-choice
  "Transform tool choice to OpenAI format"
  [tool-choice]
  (cond
    (keyword? tool-choice) (name tool-choice)
    (map? tool-choice) tool-choice
    :else tool-choice))

(defn transform-functions
  "Transform functions to OpenAI format (legacy)"
  [functions]
  (when functions
    (map (fn [func]
           (select-keys func [:name :description :parameters]))
         functions)))

(defn transform-function-call
  "Transform function call to OpenAI format (legacy)"
  [function-call]
  (cond
    (keyword? function-call) (name function-call)
    (map? function-call) function-call
    :else function-call))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-tool-calls
  "Transform OpenAI tool calls to standard format"
  [tool-calls]
  (when tool-calls
    (map (fn [tool-call]
           {:id (:id tool-call)
            :type (:type tool-call)
            :function {:name (get-in tool-call [:function :name])
                      :arguments (get-in tool-call [:function :arguments])}})
         tool-calls)))

(defn transform-function-call-response
  "Transform OpenAI function call response to standard format"
  [function-call]
  (when function-call
    {:name (:name function-call)
     :arguments (:arguments function-call)}))

(defn transform-message
  "Transform OpenAI message to standard format"
  [message]
  (cond-> {:role (keyword (:role message))
           :content (:content message)}
    (:tool_calls message) (assoc :tool-calls (transform-tool-calls (:tool_calls message)))
    (:function_call message) (assoc :function-call (transform-function-call-response (:function_call message)))))

(defn transform-choice
  "Transform OpenAI choice to standard format"
  [choice]
  {:index (:index choice)
   :message (transform-message (:message choice))
   :finish-reason (keyword (:finish_reason choice))})

(defn transform-usage
  "Transform OpenAI usage to standard format"
  [usage]
  (when usage
    {:prompt-tokens (:prompt_tokens usage)
     :completion-tokens (:completion_tokens usage)
     :total-tokens (:total_tokens usage)}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle OpenAI API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-info (get body :error {})]
    
    (case status
      401 (throw (ex-info "Authentication failed" 
                          {:type :authentication-error
                           :provider "openai"}))
      429 (throw (ex-info "Rate limit exceeded" 
                          {:type :rate-limit-error
                           :provider "openai"
                           :retry-after (get-in response [:headers "retry-after"])}))
      404 (throw (ex-info "Model not found" 
                          {:type :model-not-found-error
                           :provider "openai"
                           :model (:model body)}))
      (throw (ex-info (or (:message error-info) "Unknown error")
                      {:type :provider-error
                       :provider "openai"
                       :status status
                       :code (:code error-info)
                       :data error-info})))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for OpenAI models (in USD)"
  {"gpt-4" {:input 0.00003 :output 0.00006}
   "gpt-4-turbo" {:input 0.00001 :output 0.00003}
   "gpt-4o" {:input 0.000005 :output 0.000015}
   "gpt-4o-mini" {:input 0.00000015 :output 0.0000006}
   "gpt-3.5-turbo" {:input 0.0000005 :output 0.0000015}
   "gpt-3.5-turbo-instruct" {:input 0.0000015 :output 0.000002}})

(def default-model-mapping
  "Default model name mappings"
  {"gpt-4" "gpt-4"
   "gpt-4-turbo" "gpt-4-turbo-preview"
   "gpt-4o" "gpt-4o"
   "gpt-4o-mini" "gpt-4o-mini"
   "gpt-3.5-turbo" "gpt-3.5-turbo"})

;; ============================================================================
;; OpenAI Provider Multimethod Implementations
;; ============================================================================

(defmethod core/transform-request :openai
  [_ request config]
  (let [model (:model request)
        transformed {:model model
                    :messages (transform-messages (:messages request))
                    :max_tokens (:max-tokens request)
                    :temperature (:temperature request)
                    :top_p (:top-p request)
                    :frequency_penalty (:frequency-penalty request)
                    :presence_penalty (:presence-penalty request)
                    :stop (:stop request)
                    :stream (:stream request false)}]
    
    ;; Add function calling if present
    (cond-> transformed
      (:tools request) (assoc :tools (transform-tools (:tools request)))
      (:tool-choice request) (assoc :tool_choice (transform-tool-choice (:tool-choice request)))
      (:functions request) (assoc :functions (transform-functions (:functions request)))
      (:function-call request) (assoc :function_call (transform-function-call (:function-call request))))))

(defmethod core/make-request :openai
  [_ transformed-request thread-pools telemetry config]
  (let [url (str (:api-base config "https://api.openai.com/v1") "/chat/completions")]
    (cp/future (:api-calls thread-pools)
      (let [start-time (System/currentTimeMillis)
            response (http/post url
                                {:headers {"Authorization" (str "Bearer " (:api-key config))
                                           "Content-Type" "application/json"
                                           "User-Agent" "litellm-clj/1.0.0"}
                                 :body (json/encode transformed-request)
                                 :timeout (:timeout config 30000)
                                 :as :json})
            duration (- (System/currentTimeMillis) start-time)]
        
        ;; Handle errors
        (when (>= (:status response) 400)
          (handle-error-response :openai response))
        
        response))))

(defmethod core/transform-response :openai
  [_ response]
  (let [body (:body response)]
    {:id (:id body)
     :object (:object body)
     :created (:created body)
     :model (:model body)
     :choices (map transform-choice (:choices body))
     :usage (transform-usage (:usage body))}))

(defmethod core/supports-streaming? :openai [_] true)

(defmethod core/supports-function-calling? :openai [_] true)

(defmethod core/get-rate-limits :openai [_]
  {:requests-per-minute 3500
   :tokens-per-minute 90000})

(defmethod core/health-check :openai
  [_ thread-pools config]
  (cp/future (:health-checks thread-pools)
    (try
      (let [response (http/get (str (:api-base config "https://api.openai.com/v1") "/models")
                              {:headers {"Authorization" (str "Bearer " (:api-key config))}
                               :timeout 5000})]
        (= 200 (:status response)))
      (catch Exception e
        (log/warn "OpenAI health check failed" {:error (.getMessage e)})
        false))))

(defmethod core/get-cost-per-token :openai
  [_ model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defmethod core/transform-streaming-chunk :openai
  [_ chunk]
  (let [choice (first (:choices chunk))
        delta (:delta choice)]
    {:id (:id chunk)
     :object (:object chunk)
     :created (:created chunk)
     :model (:model chunk)
     :choices [{:index (:index choice)
               :delta {:role (keyword (:role delta))
                      :content (:content delta)}
               :finish-reason (when (:finish_reason choice)
                               (keyword (:finish_reason choice)))}]}))

(defmethod core/make-streaming-request :openai
  [_ transformed-request thread-pools config]
  (let [url (str (:api-base config "https://api.openai.com/v1") "/chat/completions")
        output-ch (streaming/create-stream-channel)]
    (go
      (try
        (let [response (http/post url
                                  {:headers {"Authorization" (str "Bearer " (:api-key config))
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode transformed-request)
                                   :timeout (:timeout config 30000)
                                   :as :stream})]
          
          ;; Handle errors
          (when (>= (:status response) 400)
            (>! output-ch (streaming/stream-error "openai" 
                                                  (str "HTTP " (:status response))
                                                  :status (:status response)))
            (streaming/close-stream! output-ch))
          
          ;; Process streaming response
          (when (= 200 (:status response))
            (let [body (:body response)
                  reader (java.io.BufferedReader. 
                          (java.io.InputStreamReader. body "UTF-8"))]
              (loop []
                (when-let [line (.readLine reader)]
                  (when-let [parsed (streaming/parse-sse-line line json/decode)]
                    (let [transformed (core/transform-streaming-chunk :openai parsed)]
                      (>! output-ch transformed)))
                  (recur)))
              (.close reader)
              (streaming/close-stream! output-ch))))
        
        (catch Exception e
          (log/error "Error in streaming request" {:error (.getMessage e)})
          (>! output-ch (streaming/stream-error "openai" (.getMessage e)))
          (streaming/close-stream! output-ch))))
    
    output-ch))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available OpenAI models"
  [provider]
  (try
    (let [response (http/get (str (:api-base provider) "/models")
                            {:headers {"Authorization" (str "Bearer " (:api-key provider))}
                             :as :json})]
      (if (= 200 (:status response))
        (map :id (get-in response [:body :data]))
        (throw (ex-info "Failed to list models" {:status (:status response)}))))
    (catch Exception e
      (log/error "Error listing OpenAI models" e)
      [])))

(defn validate-api-key
  "Validate OpenAI API key"
  [api-key]
  (try
    (let [response (http/get "https://api.openai.com/v1/models"
                            {:headers {"Authorization" (str "Bearer " api-key)}
                             :timeout 5000})]
      (= 200 (:status response)))
    (catch Exception e
      (log/debug "API key validation failed" {:error (.getMessage e)})
      false)))

;; ============================================================================
;; Provider Testing
;; ============================================================================

(defn test-openai-connection
  "Test OpenAI connection with a simple request"
  [provider thread-pools telemetry]
  (let [test-request {:model "gpt-3.5-turbo"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (core/transform-request provider test-request)
            response-future (core/make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (core/transform-response provider response)]
        {:success true
         :provider "openai"
         :model "gpt-3.5-turbo"
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "openai"
         :error (.getMessage e)
         :error-type (type e)}))))
