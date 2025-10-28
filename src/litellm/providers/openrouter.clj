(ns litellm.providers.openrouter
  "OpenRouter provider implementation for LiteLLM"
  (:require [litellm.streaming :as streaming]
            [litellm.errors :as errors]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [go >!]]))

;; ============================================================================
;; Message Transformations
;; ============================================================================

(defn transform-messages
  "Transform messages to OpenRouter format (same as OpenAI)"
  [messages]
  (map (fn [msg]
         (let [base {:role (name (:role msg))
                    :content (:content msg)}]
           (cond-> base
             (:name msg) (assoc :name (:name msg))
             (:tool-call-id msg) (assoc :tool_call_id (:tool-call-id msg)))))
       messages))

(defn transform-tools
  "Transform tools to OpenRouter format (same as OpenAI)"
  [tools]
  (when tools
    (map (fn [tool]
           (let [func (:function tool)]
             {:type (:tool-type tool "function")
              :function {:name (or (:function-name func) (:name func))
                        :description (or (:function-description func) (:description func))
                        :parameters (or (:function-parameters func) (:parameters func))}}))
         tools)))

(defn transform-tool-choice
  "Transform tool choice to OpenRouter format (same as OpenAI)"
  [tool-choice]
  (cond
    (keyword? tool-choice) (name tool-choice)
    (map? tool-choice) tool-choice
    :else tool-choice))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-tool-calls
  "Transform OpenRouter tool calls to standard format (same as OpenAI)"
  [tool-calls]
  (when tool-calls
    (map (fn [tool-call]
           {:id (:id tool-call)
            :type (:type tool-call)
            :function {:name (get-in tool-call [:function :name])
                      :arguments (get-in tool-call [:function :arguments])}})
         tool-calls)))

(defn transform-message
  "Transform OpenRouter message to standard format (same as OpenAI)"
  [message]
  (cond-> {:role (keyword (:role message))
           :content (:content message)}
    (:tool_calls message) (assoc :tool-calls (transform-tool-calls (:tool_calls message)))))

(defn transform-choice
  "Transform OpenRouter choice to standard format (same as OpenAI)"
  [choice]
  {:index (:index choice)
   :message (transform-message (:message choice))
   :finish-reason (keyword (:finish_reason choice))})

(defn transform-usage
  "Transform OpenRouter usage to standard format (same as OpenAI)"
  [usage]
  (when usage
    {:prompt-tokens (:prompt_tokens usage)
     :completion-tokens (:completion_tokens usage)
     :total-tokens (:total_tokens usage)}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle OpenRouter API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-info (get body :error {})
        message (or (:message error-info) "Unknown error")
        provider-code (:code error-info)
        request-id (get-in response [:headers "x-request-id"])]
    
    (throw (errors/http-status->error 
             status 
             "openrouter" 
             message
             :provider-code provider-code
             :request-id request-id
             :body body))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for OpenRouter models (in USD)
   Note: These are approximate and may vary as OpenRouter pricing changes"
  {"openai/gpt-4" {:input 0.00003 :output 0.00006}
   "openai/gpt-4-turbo" {:input 0.00001 :output 0.00003}
   "openai/gpt-4o" {:input 0.000005 :output 0.000015}
   "openai/gpt-3.5-turbo" {:input 0.0000005 :output 0.0000015}
   "anthropic/claude-3-opus" {:input 0.00001500 :output 0.00007500}
   "anthropic/claude-3-sonnet" {:input 0.00000300 :output 0.00001500}
   "anthropic/claude-3-haiku" {:input 0.00000025 :output 0.00000125}
   "google/gemini-pro" {:input 0.0000005 :output 0.0000015}
   "google/gemini-1.5-pro" {:input 0.00000035 :output 0.00000105}
   "meta-llama/llama-3-70b-instruct" {:input 0.0000002 :output 0.0000002}
   "meta-llama/llama-3-8b-instruct" {:input 0.0000001 :output 0.0000001}})

;; ============================================================================
;; OpenRouter Provider Implementation Functions
;; ============================================================================

(defn transform-request-impl
  "OpenRouter-specific transform-request implementation"
  [provider-name request config]
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
      (:tool-choice request) (assoc :tool_choice (transform-tool-choice (:tool-choice request))))))

(defn make-request-impl
  "OpenRouter-specific make-request implementation"
  [provider-name transformed-request thread-pool telemetry config]
  (let [url (str (:api-base config "https://openrouter.ai/api/v1") "/chat/completions")]
    (errors/wrap-http-errors
      "openrouter"
      #(let [start-time (System/currentTimeMillis)
             response (http/post url
                                 (conj {:headers {"Authorization" (str "Bearer " (:api-key config))
                                                  "HTTP-Referer" "https://github.com/unravel-team/litellm-clj"
                                                  "X-Title" "litellm-clj"
                                                  "Content-Type" "application/json"
                                                  "User-Agent" "litellm-clj/1.0.0"}
                                        :body (json/encode transformed-request)
                                        :timeout (:timeout config 30000)
                                        :async? true
                                        :as :json}
                                       (when thread-pool
                                         {:executor thread-pool})))
             duration (- (System/currentTimeMillis) start-time)]
         
         ;; Handle errors if response has error status
         (when (>= (:status @response) 400)
           (handle-error-response :openrouter @response))
         
         response))))

(defn transform-response-impl
  "OpenRouter-specific transform-response implementation"
  [provider-name response]
  (let [body (:body response)]
    {:id (:id body)
     :object (:object body)
     :created (:created body)
     :model (:model body)
     :choices (map transform-choice (:choices body))
     :usage (transform-usage (:usage body))}))

(defn supports-streaming-impl
  "OpenRouter-specific supports-streaming? implementation"
  [provider-name]
  true)

(defn supports-function-calling-impl
  "OpenRouter-specific supports-function-calling? implementation"
  [provider-name]
  true)

(defn get-rate-limits-impl
  "OpenRouter-specific get-rate-limits implementation"
  [provider-name]
  {:requests-per-minute 3500
   :tokens-per-minute 90000})

(defn health-check-impl
  "OpenRouter-specific health-check implementation"
  [provider-name thread-pool config]
  (try
    (let [response (http/get (str (:api-base config "https://openrouter.ai/api/v1") "/models")
                            (conj {:headers {"Authorization" (str "Bearer " (:api-key config))}
                                   :timeout 5000}
                                  (when thread-pool
                                    {:executor thread-pool})))]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "OpenRouter health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "OpenRouter-specific get-cost-per-token implementation"
  [provider-name model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

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
  "Transform OpenRouter streaming chunk to standard format"
  [chunk]
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

(defn transform-streaming-chunk-impl
  "OpenRouter-specific transform-streaming-chunk implementation"
  [provider-name chunk]
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

(defn make-streaming-request-impl
  "OpenRouter-specific make-streaming-request implementation"
  [provider-name transformed-request thread-pool config]
  (let [url (str (:api-base config "https://openrouter.ai/api/v1") "/chat/completions")
        output-ch (streaming/create-stream-channel)]
    (go
      (try
        (let [response (http/post url
                                  {:headers {"Authorization" (str "Bearer " (:api-key config))
                                             "HTTP-Referer" "https://github.com/unravel-team/litellm-clj"
                                             "X-Title" "litellm-clj"
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode transformed-request)
                                   :timeout (:timeout config 30000)
                                   :as :stream})]
          
          ;; Handle errors
          (when (>= (:status response) 400)
            (>! output-ch (streaming/stream-error "openrouter" 
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
                    (let [transformed (transform-streaming-chunk-impl :openrouter parsed)]
                      (>! output-ch transformed)))
                  (recur)))
              (.close reader)
              (streaming/close-stream! output-ch))))
        
        (catch Exception e
          (log/error "Error in streaming request" {:error (.getMessage e)})
          (>! output-ch (streaming/stream-error "openrouter" (.getMessage e)))
          (streaming/close-stream! output-ch))))
    
    output-ch))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available OpenRouter models"
  [provider]
  (try
    (let [response (http/get (str (:api-base provider) "/models")
                            {:headers {"Authorization" (str "Bearer " (:api-key provider))}
                             :as :json})]
      (if (= 200 (:status response))
        (map :id (get-in response [:body :data]))
        (throw (ex-info "Failed to list models" {:status (:status response)}))))
    (catch Exception e
      (log/error "Error listing OpenRouter models" e)
      [])))

(defn validate-api-key
  "Validate OpenRouter API key"
  [api-key]
  (try
    (let [response (http/get "https://openrouter.ai/api/v1/models"
                            {:headers {"Authorization" (str "Bearer " api-key)}
                             :timeout 5000})]
      (= 200 (:status response)))
    (catch Exception e
      (log/debug "API key validation failed" {:error (.getMessage e)})
      false)))

;; ============================================================================
;; Provider Testing
;; ============================================================================

(defn test-openrouter-connection
  "Test OpenRouter connection with a simple request"
  [provider thread-pool telemetry]
  (let [test-request {:model "openai/gpt-3.5-turbo"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (transform-request-impl :openrouter test-request provider)
            response-future (make-request-impl :openrouter transformed thread-pool telemetry provider)
            response @response-future
            standard-response (transform-response-impl :openrouter response)]
        {:success true
         :provider "openrouter"
         :model "openai/gpt-3.5-turbo"
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "openrouter"
         :error (.getMessage e)
         :error-type (type e)}))))
