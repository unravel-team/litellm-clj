(ns litellm.providers.core
  "Core provider protocol and utilities for LiteLLM"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [litellm.schemas :as schemas]
            [litellm.errors :as errors]
            [litellm.providers.openai :as openai]
            [litellm.providers.anthropic :as anthropic]
            [litellm.providers.gemini :as gemini]
            [litellm.providers.mistral :as mistral]
            [litellm.providers.ollama :as ollama]
            [litellm.providers.openrouter :as openrouter]))

;; ============================================================================
;; Provider Multimethods and Implementations
;; ============================================================================

;; transform-request
;; ----------------------------------------------------------------------------

(defmulti transform-request
  "Transform a standard request to provider-specific format"
  (fn [provider-name request config] provider-name))

(defmethod transform-request :openai [provider-name request config]
  (openai/transform-request-impl provider-name request config))

(defmethod transform-request :anthropic [provider-name request config]
  (anthropic/transform-request-impl provider-name request config))

(defmethod transform-request :gemini [provider-name request config]
  (gemini/transform-request-impl provider-name request config))

(defmethod transform-request :mistral [provider-name request config]
  (mistral/transform-request-impl provider-name request config))

(defmethod transform-request :ollama [provider-name request config]
  (ollama/transform-request-impl provider-name request config))

(defmethod transform-request :openrouter [provider-name request config]
  (openrouter/transform-request-impl provider-name request config))

;; make-request
;; ----------------------------------------------------------------------------

(defmulti make-request
  "Make HTTP request to provider API, returns a future"
  (fn [provider-name transformed-request thread-pool telemetry config] provider-name))

(defmethod make-request :openai [provider-name transformed-request thread-pool telemetry config]
  (openai/make-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-request :anthropic [provider-name transformed-request thread-pool telemetry config]
  (anthropic/make-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-request :gemini [provider-name transformed-request thread-pool telemetry config]
  (gemini/make-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-request :mistral [provider-name transformed-request thread-pool telemetry config]
  (mistral/make-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-request :ollama [provider-name transformed-request thread-pool telemetry config]
  (ollama/make-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-request :openrouter [provider-name transformed-request thread-pool telemetry config]
  (openrouter/make-request-impl provider-name transformed-request thread-pool telemetry config))

;; make-streaming-request
;; ----------------------------------------------------------------------------

(defmulti make-streaming-request
  "Make streaming HTTP request to provider API, returns a core.async channel"
  (fn [provider-name transformed-request thread-pool config] provider-name))

(defmethod make-streaming-request :openai [provider-name transformed-request thread-pool config]
  (openai/make-streaming-request-impl provider-name transformed-request thread-pool config))

(defmethod make-streaming-request :anthropic [provider-name transformed-request thread-pool config]
  (anthropic/make-streaming-request-impl provider-name transformed-request thread-pool config))

(defmethod make-streaming-request :gemini [provider-name transformed-request thread-pool config]
  (gemini/make-streaming-request-impl provider-name transformed-request thread-pool config))

(defmethod make-streaming-request :openrouter [provider-name transformed-request thread-pool config]
  (openrouter/make-streaming-request-impl provider-name transformed-request thread-pool config))

;; transform-response
;; ----------------------------------------------------------------------------

(defmulti transform-response
  "Transform provider response to standard format"
  (fn [provider-name response] provider-name))

(defmethod transform-response :openai [provider-name response]
  (openai/transform-response-impl provider-name response))

(defmethod transform-response :anthropic [provider-name response]
  (anthropic/transform-response-impl provider-name response))

(defmethod transform-response :gemini [provider-name response]
  (gemini/transform-response-impl provider-name response))

(defmethod transform-response :mistral [provider-name response]
  (mistral/transform-response-impl provider-name response))

(defmethod transform-response :ollama [provider-name response]
  (ollama/transform-response-impl provider-name response))

(defmethod transform-response :openrouter [provider-name response]
  (openrouter/transform-response-impl provider-name response))

;; transform-streaming-chunk
;; ----------------------------------------------------------------------------

(defmulti transform-streaming-chunk
  "Transform provider streaming chunk to standard format"
  (fn [provider-name chunk] provider-name))

(defmethod transform-streaming-chunk :openai [provider-name chunk]
  (openai/transform-streaming-chunk-impl provider-name chunk))

(defmethod transform-streaming-chunk :anthropic [provider-name chunk]
  (anthropic/transform-streaming-chunk-impl provider-name chunk))

(defmethod transform-streaming-chunk :gemini [provider-name chunk]
  (gemini/transform-streaming-chunk-impl provider-name chunk))

(defmethod transform-streaming-chunk :openrouter [provider-name chunk]
  (openrouter/transform-streaming-chunk-impl provider-name chunk))

;; supports-streaming?
;; ----------------------------------------------------------------------------

(defmulti supports-streaming?
  "Check if provider supports streaming responses"
  identity)

(defmethod supports-streaming? :default [_] false)

(defmethod supports-streaming? :openai [provider-name]
  (openai/supports-streaming-impl provider-name))

(defmethod supports-streaming? :anthropic [provider-name]
  (anthropic/supports-streaming-impl provider-name))

(defmethod supports-streaming? :gemini [provider-name]
  (gemini/supports-streaming-impl provider-name))

(defmethod supports-streaming? :mistral [provider-name]
  (mistral/supports-streaming-impl provider-name))

(defmethod supports-streaming? :ollama [provider-name]
  (ollama/supports-streaming-impl provider-name))

(defmethod supports-streaming? :openrouter [provider-name]
  (openrouter/supports-streaming-impl provider-name))

;; supports-function-calling?
;; ----------------------------------------------------------------------------

(defmulti supports-function-calling?
  "Check if provider supports function calling"
  identity)

(defmethod supports-function-calling? :default [_] false)

(defmethod supports-function-calling? :openai [provider-name]
  (openai/supports-function-calling-impl provider-name))

(defmethod supports-function-calling? :anthropic [provider-name]
  (anthropic/supports-function-calling-impl provider-name))

(defmethod supports-function-calling? :gemini [provider-name]
  (gemini/supports-function-calling-impl provider-name))

(defmethod supports-function-calling? :mistral [provider-name]
  (mistral/supports-function-calling-impl provider-name))

(defmethod supports-function-calling? :ollama [provider-name]
  (ollama/supports-function-calling-impl provider-name))

(defmethod supports-function-calling? :openrouter [provider-name]
  (openrouter/supports-function-calling-impl provider-name))

;; get-rate-limits
;; ----------------------------------------------------------------------------

(defmulti get-rate-limits
  "Get provider rate limits as a map"
  identity)

(defmethod get-rate-limits :default [_]
  {:requests-per-minute 1000
   :tokens-per-minute 50000})

(defmethod get-rate-limits :openai [provider-name]
  (openai/get-rate-limits-impl provider-name))

(defmethod get-rate-limits :anthropic [provider-name]
  (anthropic/get-rate-limits-impl provider-name))

(defmethod get-rate-limits :gemini [provider-name]
  (gemini/get-rate-limits-impl provider-name))

(defmethod get-rate-limits :mistral [provider-name]
  (mistral/get-rate-limits-impl provider-name))

(defmethod get-rate-limits :ollama [provider-name]
  (ollama/get-rate-limits-impl provider-name))

(defmethod get-rate-limits :openrouter [provider-name]
  (openrouter/get-rate-limits-impl provider-name))

;; health-check
;; ----------------------------------------------------------------------------

(defmulti health-check
  "Perform health check, returns a future with boolean result"
  (fn [provider-name thread-pool config] provider-name))

(defmethod health-check :openai [provider-name thread-pool config]
  (openai/health-check-impl provider-name thread-pool config))

(defmethod health-check :anthropic [provider-name thread-pool config]
  (anthropic/health-check-impl provider-name thread-pool config))

(defmethod health-check :gemini [provider-name thread-pool config]
  (gemini/health-check-impl provider-name thread-pool config))

(defmethod health-check :mistral [provider-name thread-pool config]
  (mistral/health-check-impl provider-name thread-pool config))

(defmethod health-check :ollama [provider-name thread-pool config]
  (ollama/health-check-impl provider-name thread-pool config))

(defmethod health-check :openrouter [provider-name thread-pool config]
  (openrouter/health-check-impl provider-name thread-pool config))

;; get-cost-per-token
;; ----------------------------------------------------------------------------

(defmulti get-cost-per-token
  "Get cost per token for a specific model"
  (fn [provider-name model] provider-name))

(defmethod get-cost-per-token :default [_ _]
  {:input 0.0 :output 0.0})

(defmethod get-cost-per-token :openai [provider-name model]
  (openai/get-cost-per-token-impl provider-name model))

(defmethod get-cost-per-token :anthropic [provider-name model]
  (anthropic/get-cost-per-token-impl provider-name model))

(defmethod get-cost-per-token :gemini [provider-name model]
  (gemini/get-cost-per-token-impl provider-name model))

(defmethod get-cost-per-token :mistral [provider-name model]
  (mistral/get-cost-per-token-impl provider-name model))

(defmethod get-cost-per-token :ollama [provider-name model]
  (ollama/get-cost-per-token-impl provider-name model))

(defmethod get-cost-per-token :openrouter [provider-name model]
  (openrouter/get-cost-per-token-impl provider-name model))

;; transform-embedding-request
;; ----------------------------------------------------------------------------

(defmulti transform-embedding-request
  "Transform a standard embedding request to provider-specific format"
  (fn [provider-name request config] provider-name))

(defmethod transform-embedding-request :openai [provider-name request config]
  (openai/transform-embedding-request-impl provider-name request config))

(defmethod transform-embedding-request :mistral [provider-name request config]
  (mistral/transform-embedding-request-impl provider-name request config))

(defmethod transform-embedding-request :gemini [provider-name request config]
  (gemini/transform-embedding-request-impl provider-name request config))

;; make-embedding-request
;; ----------------------------------------------------------------------------

(defmulti make-embedding-request
  "Make HTTP request to provider embedding API, returns a future"
  (fn [provider-name transformed-request thread-pool telemetry config] provider-name))

(defmethod make-embedding-request :openai [provider-name transformed-request thread-pool telemetry config]
  (openai/make-embedding-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-embedding-request :mistral [provider-name transformed-request thread-pool telemetry config]
  (mistral/make-embedding-request-impl provider-name transformed-request thread-pool telemetry config))

(defmethod make-embedding-request :gemini [provider-name transformed-request thread-pool telemetry config]
  (gemini/make-embedding-request-impl provider-name transformed-request thread-pool telemetry config))

;; transform-embedding-response
;; ----------------------------------------------------------------------------

(defmulti transform-embedding-response
  "Transform provider embedding response to standard format"
  (fn [provider-name response] provider-name))

(defmethod transform-embedding-response :openai [provider-name response]
  (openai/transform-embedding-response-impl provider-name response))

(defmethod transform-embedding-response :mistral [provider-name response]
  (mistral/transform-embedding-response-impl provider-name response))

(defmethod transform-embedding-response :gemini [provider-name response]
  (gemini/transform-embedding-response-impl provider-name response))

;; supports-embeddings?
;; ----------------------------------------------------------------------------

(defmulti supports-embeddings?
  "Check if provider supports embeddings"
  identity)

(defmethod supports-embeddings? :default [_] false)

(defmethod supports-embeddings? :openai [provider-name]
  (openai/supports-embeddings-impl provider-name))

(defmethod supports-embeddings? :mistral [provider-name]
  (mistral/supports-embeddings-impl provider-name))

(defmethod supports-embeddings? :gemini [provider-name]
  (gemini/supports-embeddings-impl provider-name))

;; ============================================================================
;; Provider Validation
;; ============================================================================

(defn validate-request
  "Validate request against provider capabilities"
  [provider-name request]
  (when (and (:stream request) (not (supports-streaming? provider-name)))
    (throw (errors/unsupported-feature 
             (name provider-name) 
             :streaming
             :message "Provider doesn't support streaming")))
  
  (when (and (or (:tools request) (:functions request)) 
             (not (supports-function-calling? provider-name)))
    (throw (errors/unsupported-feature 
             (name provider-name)
             :function-calling
             :message "Provider doesn't support function calling")))
  
  (when-not (schemas/valid-request? request)
    (throw (errors/invalid-request
             "Invalid request format"
             :request request
             :errors (schemas/explain-request request)))))

(defn validate-embedding-request
  "Validate embedding request against provider capabilities"
  [provider-name request]
  (when-not (supports-embeddings? provider-name)
    (throw (errors/unsupported-feature
             (name provider-name)
             :embeddings
             :message "Provider doesn't support embeddings")))
  
  (when-not (schemas/valid-embedding-request? request)
    (throw (errors/invalid-request
             "Invalid embedding request format"
             :request request
             :errors (schemas/explain-embedding-request request)))))

;; ============================================================================
;; Model String Parsing
;; ============================================================================

(defn extract-provider-name
  "Extract provider name from model string (e.g., 'openai/gpt-4' -> :openai)"
  [model]
  (if (string? model)
    (let [parts (str/split model #"/")]
      (keyword (if (> (count parts) 1)
                 (first parts)
                 "openai"))) ; Default to openai for bare model names
    (keyword (str model))))

(defn extract-model-name
  "Extract actual model name from model string (e.g., 'openai/gpt-4' -> 'gpt-4')"
  [model]
  (if (string? model)
    (let [parts (str/split model #"/")]
      (if (> (count parts) 1)
        (str/join "/" (rest parts))
        model))
    (str model)))

(defn parse-model-string
  "Parse model string into provider (keyword) and model components"
  [model]
  {:provider (extract-provider-name model)
   :model (extract-model-name model)
   :original model})

;; ============================================================================
;; Standard Message Transformations
;; ============================================================================

(defn transform-messages-to-openai
  "Transform messages to OpenAI format"
  [messages]
  (map (fn [msg]
         {:role (name (:role msg))
          :content (:content msg)})
       messages))

(defn transform-messages-from-openai
  "Transform messages from OpenAI format"
  [messages]
  (map (fn [msg]
         {:role (keyword (:role msg))
          :content (:content msg)})
       messages))

(defn transform-tools-to-openai
  "Transform tools to OpenAI format"
  [tools]
  (when tools
    (map (fn [tool]
           {:type (:tool-type tool "function")
            :function {:name (:function-name (:function tool))
                      :description (:function-description (:function tool))
                      :parameters (:function-parameters (:function tool))}})
         tools)))

;; ============================================================================
;; Standard Response Transformations
;; ============================================================================

(defn create-standard-response
  "Create a standard response format"
  [id model choices usage & {:keys [object created]}]
  {:id (or id (str "chatcmpl-" (java.util.UUID/randomUUID)))
   :object (or object "chat.completion")
   :created (or created (quot (System/currentTimeMillis) 1000))
   :model model
   :choices choices
   :usage usage})

(defn create-standard-choice
  "Create a standard choice format"
  [index message finish-reason]
  {:index index
   :message message
   :finish-reason finish-reason})

(defn create-standard-message
  "Create a standard message format"
  [role content & {:keys [tool-calls function-call]}]
  (cond-> {:role role :content content}
    tool-calls (assoc :tool-calls tool-calls)
    function-call (assoc :function-call function-call)))

(defn create-standard-usage
  "Create a standard usage format"
  [prompt-tokens completion-tokens]
  {:prompt-tokens prompt-tokens
   :completion-tokens completion-tokens
   :total-tokens (+ (or prompt-tokens 0) (or completion-tokens 0))})

;; ============================================================================
;; Error Handling (Legacy - kept for backward compatibility)
;; ============================================================================

;; These functions are now thin wrappers around the new errors namespace
;; and are kept for backward compatibility. New code should use litellm.errors directly.

(defn provider-error
  "Create a provider-specific error (DEPRECATED - use litellm.errors/provider-error)"
  [provider message & {:keys [status code data]}]
  (errors/provider-error
    (if (keyword? provider) (name provider) (str provider))
    message
    :http-status status
    :provider-code code))

(defn rate-limit-error
  "Create a rate limit error (DEPRECATED - use litellm.errors/rate-limit)"
  [provider & {:keys [retry-after]}]
  (errors/rate-limit
    (if (keyword? provider) (name provider) (str provider))
    "Rate limit exceeded"
    :retry-after retry-after))

(defn authentication-error
  "Create an authentication error (DEPRECATED - use litellm.errors/authentication-error)"
  [provider]
  (errors/authentication-error
    (if (keyword? provider) (name provider) (str provider))
    "Authentication failed"))

(defn model-not-found-error
  "Create a model not found error (DEPRECATED - use litellm.errors/model-not-found)"
  [provider model]
  (errors/model-not-found
    (if (keyword? provider) (name provider) (str provider))
    model))

(defn quota-exceeded-error
  "Create a quota exceeded error (DEPRECATED - use litellm.errors/quota-exceeded)"
  [provider]
  (errors/quota-exceeded
    (if (keyword? provider) (name provider) (str provider))
    "Quota exceeded"))

;; ============================================================================
;; Provider Discovery
;; ============================================================================

(defn list-available-providers
  "List all available providers by checking multimethod implementations"
  []
  (-> transform-request methods keys set (disj :default)))

(defn provider-available?
  "Check if a provider is available (has multimethod implementations)"
  [provider-name]
  (contains? (list-available-providers) provider-name))

;; ============================================================================
;; Provider Configuration Helpers
;; ============================================================================

(defn default-provider-config
  "Get default configuration for a provider"
  [provider-name]
  {:provider provider-name
   :timeout 30000
   :max-retries 3
   :rate-limit 1000})

(defn merge-provider-config
  "Merge user config with provider defaults"
  [provider-name user-config]
  (merge (default-provider-config provider-name) user-config))

(defn validate-provider-config
  "Validate provider configuration"
  [config]
  (when-not (schemas/valid-config? config)
    (throw (errors/invalid-config
             "Invalid provider configuration"
             :config config
             :errors (schemas/explain-config config))))
  config)

;; ============================================================================
;; Provider Utilities
;; ============================================================================

(defn provider-supports-model?
  "Check if provider supports a specific model"
  [provider-name model]
  ;; This is a basic implementation - providers can override this
  (let [model-provider (extract-provider-name model)]
    (= provider-name model-provider)))

(defn estimate-tokens
  "Rough estimation of token count for text"
  [text]
  (when text
    ;; Very rough estimation: ~4 characters per token
    (max 1 (quot (count text) 4))))

(defn estimate-request-tokens
  "Estimate token count for a request"
  [request]
  (let [message-tokens (reduce + 0 (map #(estimate-tokens (:content %)) (:messages request)))
        system-tokens (if-let [system-msg (first (filter #(= :system (:role %)) (:messages request)))]
                       (estimate-tokens (:content system-msg))
                       0)]
    (+ message-tokens system-tokens)))

(defn calculate-cost
  "Calculate cost for a request/response"
  [provider-name model prompt-tokens completion-tokens]
  (try
    (let [cost-per-token (get-cost-per-token provider-name model)]
      (+ (* prompt-tokens (:input cost-per-token 0))
         (* completion-tokens (:output cost-per-token 0))))
    (catch Exception e
      (log/warn "Error calculating cost" {:provider provider-name :model model :error (.getMessage e)})
      0.0)))

;; ============================================================================
;; Provider Health and Status
;; ============================================================================

(defn provider-status
  "Get comprehensive provider status"
  [provider-name]
  {:name provider-name
   :supports-streaming (supports-streaming? provider-name)
   :supports-function-calling (supports-function-calling? provider-name)
   :rate-limits (get-rate-limits provider-name)})

(defn log-provider-status
  "Log provider status for debugging"
  [provider-name]
  (log/info "Provider status" (provider-status provider-name)))

;; ============================================================================
;; Provider Testing Utilities
;; ============================================================================

(defn test-provider
  "Test provider with a simple request"
  [provider-name thread-pool telemetry config]
  (let [test-request {:model "test"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 1}]
    (try
      (validate-request provider-name test-request)
      (let [transformed (transform-request provider-name test-request config)
            response-future (make-request provider-name transformed thread-pool telemetry config)
            response @response-future
            standard-response (transform-response provider-name response)]
        {:success true
         :provider provider-name
         :response standard-response})
      (catch Exception e
        {:success false
         :provider provider-name
         :error (.getMessage e)}))))

;; ============================================================================
;; Provider Comparison Utilities
;; ============================================================================

(defn compare-providers
  "Compare multiple providers on various capabilities"
  [provider-names]
  (map (fn [provider-name]
         (assoc (provider-status provider-name)
                :health-check-available (try
                                        (some? (health-check provider-name nil nil))
                                        (catch Exception _ false))))
       provider-names))

(defn find-providers-for-model
  "Find all providers that support a given model"
  [provider-names model]
  (filter #(provider-supports-model? % model) provider-names))
