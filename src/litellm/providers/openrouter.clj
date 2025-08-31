(ns litellm.providers.openrouter
  "OpenRouter provider implementation for LiteLLM"
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
           {:type (:tool-type tool "function")
            :function (select-keys (:function tool) [:name :description :parameters])})
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
        error-info (get body :error {})]
    
    (case status
      401 (throw (ex-info "Authentication failed" 
                          {:type :authentication-error
                           :provider "openrouter"}))
      429 (throw (ex-info "Rate limit exceeded" 
                          {:type :rate-limit-error
                           :provider "openrouter"
                           :retry-after (get-in response [:headers "retry-after"])}))
      404 (throw (ex-info "Model not found" 
                          {:type :model-not-found-error
                           :provider "openrouter"
                           :model (:model body)}))
      (throw (ex-info (or (:message error-info) "Unknown error")
                      {:type :provider-error
                       :provider "openrouter"
                       :status status
                       :code (:code error-info)
                       :data error-info})))))

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
;; OpenRouter Provider Record
;; ============================================================================

(defrecord OpenRouterProvider [api-key api-base rate-limits cost-map timeout]
  core/LLMProvider
  
  (provider-name [_] "openrouter")
  
  (transform-request [provider request]
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
  
  (make-request [provider transformed-request thread-pools telemetry]
    (let [url (str (:api-base provider) "/chat/completions")]
      (cp/future (:api-calls thread-pools)
        (let [start-time (System/currentTimeMillis)
              response (http/post url
                                  {:headers {"Authorization" (str "Bearer " (:api-key provider))
                                             "HTTP-Referer" "https://github.com/unravel-team/clj-litellm"
                                             "X-Title" "clj-litellm"
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
       :object (:object body)
       :created (:created body)
       :model (:model body)
       :choices (map transform-choice (:choices body))
       :usage (transform-usage (:usage body))}))
  
  (supports-streaming? [_] true)
  
  (supports-function-calling? [_] true)
  
  (get-rate-limits [provider]
    (:rate-limits provider {:requests-per-minute 3500
                           :tokens-per-minute 90000}))
  
  (health-check [provider thread-pools]
    (cp/future (:health-checks thread-pools)
      (try
        (let [response (http/get (str (:api-base provider) "/models")
                                {:headers {"Authorization" (str "Bearer " (:api-key provider))}
                                 :timeout 5000})]
          (= 200 (:status response)))
        (catch Exception e
          (log/warn "OpenRouter health check failed" {:error (.getMessage e)})
          false))))
  
  (get-cost-per-token [provider model]
    (get (:cost-map provider) model {:input 0.0 :output 0.0})))

;; ============================================================================
;; Provider Factory
;; ============================================================================

(defn create-openrouter-provider
  "Create OpenRouter provider instance"
  [config]
  (map->OpenRouterProvider
    {:api-key (:api-key config)
     :api-base (:api-base config "https://openrouter.ai/api/v1")
     :rate-limits (:rate-limits config {:requests-per-minute 3500
                                       :tokens-per-minute 90000})
     :cost-map (merge default-cost-map (:cost-map config {}))
     :timeout (:timeout config 30000)}))

;; ============================================================================
;; Provider Registration
;; ============================================================================

(defn register-openrouter-provider!
  "Register OpenRouter provider in the global registry"
  []
  (core/register-provider! "openrouter" create-openrouter-provider))

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

(defn handle-streaming-response
  "Handle streaming response from OpenRouter"
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
  [provider thread-pools telemetry]
  (let [test-request {:model "openai/gpt-3.5-turbo"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (core/transform-request provider test-request)
            response-future (core/make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (core/transform-response provider response)]
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

;; Auto-register the provider when namespace is loaded
(register-openrouter-provider!)
