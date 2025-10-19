(ns litellm.providers.mistral
  "Mistral AI provider implementation for LiteLLM"
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
  "Transform messages to Mistral format (OpenAI-compatible)"
  [messages]
  (map (fn [msg]
         (let [base {:role (name (:role msg))
                    :content (:content msg)}]
           (cond-> base
             (:name msg) (assoc :name (:name msg))
             (:tool-call-id msg) (assoc :tool_call_id (:tool-call-id msg)))))
       messages))

(defn add-reasoning-system-prompt
  "Add reasoning system prompt for magistral models based on reasoning_effort"
  [messages reasoning-effort]
  (when reasoning-effort
    (let [reasoning-prompt "When solving problems, think step-by-step in <think> tags before providing your final answer. Break down complex problems into smaller steps and show your reasoning process clearly."]
      ;; Find existing system message
      (let [system-msg (first (filter #(= "system" (:role %)) messages))
            other-msgs (remove #(= "system" (:role %)) messages)]
        (if system-msg
          ;; Prepend reasoning prompt to existing system message
          (cons (assoc system-msg :content (str reasoning-prompt "\n\n" (:content system-msg)))
                other-msgs)
          ;; Add new system message with reasoning prompt
          (cons {:role "system" :content reasoning-prompt}
                messages))))))

(defn transform-tools
  "Transform tools to Mistral format (OpenAI-compatible)"
  [tools]
  (when tools
    (map (fn [tool]
           {:type (:tool-type tool "function")
            :function (select-keys (:function tool) [:name :description :parameters])})
         tools)))

(defn transform-tool-choice
  "Transform tool choice to Mistral format"
  [tool-choice]
  (cond
    (keyword? tool-choice) (name tool-choice)
    (map? tool-choice) tool-choice
    :else tool-choice))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-tool-calls
  "Transform Mistral tool calls to standard format"
  [tool-calls]
  (when tool-calls
    (map (fn [tool-call]
           {:id (:id tool-call)
            :type (:type tool-call)
            :function {:name (get-in tool-call [:function :name])
                      :arguments (get-in tool-call [:function :arguments])}})
         tool-calls)))

(defn transform-message
  "Transform Mistral message to standard format"
  [message]
  (cond-> {:role (keyword (:role message))
           :content (:content message)}
    (:tool_calls message) (assoc :tool-calls (transform-tool-calls (:tool_calls message)))))

(defn transform-choice
  "Transform Mistral choice to standard format"
  [choice]
  {:index (:index choice)
   :message (transform-message (:message choice))
   :finish-reason (keyword (:finish_reason choice))})

(defn transform-usage
  "Transform Mistral usage to standard format"
  [usage]
  (when usage
    {:prompt-tokens (:prompt_tokens usage)
     :completion-tokens (:completion_tokens usage)
     :total-tokens (:total_tokens usage)}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle Mistral API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-info (get body :error {})]
    
    (case status
      401 (throw (ex-info "Authentication failed" 
                          {:type :authentication-error
                           :provider "mistral"}))
      429 (throw (ex-info "Rate limit exceeded" 
                          {:type :rate-limit-error
                           :provider "mistral"
                           :retry-after (get-in response [:headers "retry-after"])}))
      404 (throw (ex-info "Model not found" 
                          {:type :model-not-found-error
                           :provider "mistral"
                           :model (:model body)}))
      (throw (ex-info (or (:message error-info) "Unknown error")
                      {:type :provider-error
                       :provider "mistral"
                       :status status
                       :code (:code error-info)
                       :data error-info})))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for Mistral models (in USD)
   Prices from https://mistral.ai/technology/#pricing"
  {"mistral-small-latest" {:input 0.000001 :output 0.000003}
   "mistral-small-2412" {:input 0.000001 :output 0.000003}
   "mistral-medium-latest" {:input 0.0000027 :output 0.0000081}
   "mistral-medium-2412" {:input 0.0000027 :output 0.0000081}
   "mistral-large-latest" {:input 0.000002 :output 0.000006}
   "mistral-large-2407" {:input 0.000002 :output 0.000006}
   "mistral-large-2411" {:input 0.000002 :output 0.000006}
   "magistral-small-2506" {:input 0.000001 :output 0.000003}
   "magistral-medium-2506" {:input 0.0000027 :output 0.0000081}
   "open-mistral-7b" {:input 0.00000025 :output 0.00000025}
   "open-mixtral-8x7b" {:input 0.0000007 :output 0.0000007}
   "open-mixtral-8x22b" {:input 0.000002 :output 0.000006}
   "codestral-latest" {:input 0.000001 :output 0.000003}
   "codestral-2405" {:input 0.000001 :output 0.000003}
   "open-mistral-nemo" {:input 0.0000003 :output 0.0000003}
   "open-mistral-nemo-2407" {:input 0.0000003 :output 0.0000003}
   "open-codestral-mamba" {:input 0.00000025 :output 0.00000025}
   "codestral-mamba-latest" {:input 0.00000025 :output 0.00000025}
   "mistral-embed" {:input 0.0000001 :output 0.0}})

(def magistral-models
  "Models that support reasoning with reasoning_effort parameter"
  #{"magistral-small-2506" "magistral-medium-2506"})

(defn supports-reasoning?
  "Check if a model supports reasoning"
  [model]
  (contains? magistral-models model))

;; ============================================================================
;; Mistral Provider Multimethod Implementations
;; ============================================================================

(defmethod core/transform-request :mistral
  [_ request config]
  (let [model (:model request)
        reasoning-effort (:reasoning-effort request)
        messages (:messages request)
        ;; Add reasoning system prompt if applicable
        transformed-messages (if (and reasoning-effort (supports-reasoning? model))
                              (add-reasoning-system-prompt 
                                (transform-messages messages)
                                reasoning-effort)
                              (transform-messages messages))
        transformed {:model model
                    :messages transformed-messages
                    :max_tokens (:max-tokens request)
                    :temperature (:temperature request)
                    :top_p (:top-p request)
                    :stop (:stop request)
                    :stream (:stream request false)}]
    
    ;; Add function calling if present
    (cond-> transformed
      (:tools request) (assoc :tools (transform-tools (:tools request)))
      (:tool-choice request) (assoc :tool_choice (transform-tool-choice (:tool-choice request))))))

(defmethod core/make-request :mistral
  [_ transformed-request thread-pools telemetry config]
  (let [url (str (:api-base config "https://api.mistral.ai/v1") "/chat/completions")]
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
          (handle-error-response :mistral response))
        
        response))))

(defmethod core/transform-response :mistral
  [_ response]
  (let [body (:body response)]
    {:id (:id body)
     :object (:object body)
     :created (:created body)
     :model (:model body)
     :choices (map transform-choice (:choices body))
     :usage (transform-usage (:usage body))}))

(defmethod core/supports-streaming? :mistral [_] true)

(defmethod core/supports-function-calling? :mistral [_] true)

(defmethod core/get-rate-limits :mistral [_]
  {:requests-per-minute 1000
   :tokens-per-minute 1000000})

(defmethod core/health-check :mistral
  [_ thread-pools config]
  (cp/future (:health-checks thread-pools)
    (try
      (let [response (http/get (str (:api-base config "https://api.mistral.ai/v1") "/models")
                              {:headers {"Authorization" (str "Bearer " (:api-key config))}
                               :timeout 5000})]
        (= 200 (:status response)))
      (catch Exception e
        (log/warn "Mistral health check failed" {:error (.getMessage e)})
        false))))

(defmethod core/get-cost-per-token :mistral
  [_ model]
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
  "Transform Mistral streaming chunk to standard format"
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
  "Handle streaming response from Mistral"
  [response callback]
  (let [body (:body response)]
    (doseq [line (str/split-lines body)]
      (when-let [parsed (parse-sse-line line)]
        (let [transformed (transform-streaming-chunk parsed)]
          (callback transformed))))))

;; ============================================================================
;; Embedding Support
;; ============================================================================

(defn create-embedding-request
  "Transform embedding request to Mistral format"
  [input model]
  {:input (if (string? input) [input] input)
   :model (or model "mistral-embed")})

(defn make-embedding-request
  "Make embedding request to Mistral API"
  [provider input model thread-pools]
  (let [url (str (:api-base provider) "/embeddings")
        request-body (create-embedding-request input model)]
    (cp/future (:api-calls thread-pools)
      (let [response (http/post url
                               {:headers {"Authorization" (str "Bearer " (:api-key provider))
                                          "Content-Type" "application/json"
                                          "User-Agent" "litellm-clj/1.0.0"}
                                :body (json/encode request-body)
                                :timeout (:timeout provider 30000)
                                :as :json})]
        
        ;; Handle errors
        (when (>= (:status response) 400)
          (handle-error-response provider response))
        
        (let [body (:body response)]
          {:object (:object body)
           :data (map (fn [item]
                       {:object (:object item)
                        :embedding (:embedding item)
                        :index (:index item)})
                     (:data body))
           :model (:model body)
           :usage (transform-usage (:usage body))})))))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available Mistral models"
  [provider]
  (try
    (let [response (http/get (str (:api-base provider) "/models")
                            {:headers {"Authorization" (str "Bearer " (:api-key provider))}
                             :as :json})]
      (if (= 200 (:status response))
        (map :id (get-in response [:body :data]))
        (throw (ex-info "Failed to list models" {:status (:status response)}))))
    (catch Exception e
      (log/error "Error listing Mistral models" e)
      [])))

(defn validate-api-key
  "Validate Mistral API key"
  [api-key]
  (try
    (let [response (http/get "https://api.mistral.ai/v1/models"
                            {:headers {"Authorization" (str "Bearer " api-key)}
                             :timeout 5000})]
      (= 200 (:status response)))
    (catch Exception e
      (log/debug "API key validation failed" {:error (.getMessage e)})
      false)))

;; ============================================================================
;; Provider Testing
;; ============================================================================

(defn test-mistral-connection
  "Test Mistral connection with a simple request"
  [provider thread-pools telemetry]
  (let [test-request {:model "mistral-small-latest"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (core/transform-request provider test-request)
            response-future (core/make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (core/transform-response provider response)]
        {:success true
         :provider "mistral"
         :model "mistral-small-latest"
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "mistral"
         :error (.getMessage e)
         :error-type (type e)}))))
