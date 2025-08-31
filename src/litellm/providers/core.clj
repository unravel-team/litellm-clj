(ns litellm.providers.core
  "Core provider protocol and utilities for LiteLLM"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [litellm.schemas :as schemas]))

;; ============================================================================
;; Provider Protocol
;; ============================================================================

(defprotocol LLMProvider
  "Protocol for LLM providers"
  
  (provider-name [provider] 
    "Get the name of this provider (e.g., 'openai', 'anthropic')")
  
  (transform-request [provider request] 
    "Transform a standard request to provider-specific format")
  
  (make-request [provider transformed-request thread-pools telemetry] 
    "Make HTTP request to provider API, returns a future")
  
  (transform-response [provider response] 
    "Transform provider response to standard format")
  
  (supports-streaming? [provider] 
    "Check if provider supports streaming responses")
  
  (supports-function-calling? [provider] 
    "Check if provider supports function calling")
  
  (get-rate-limits [provider] 
    "Get provider rate limits as a map")
  
  (health-check [provider thread-pools] 
    "Perform health check, returns a future with boolean result")
  
  (get-cost-per-token [provider model] 
    "Get cost per token for a specific model"))

;; ============================================================================
;; Provider Validation
;; ============================================================================

(defn validate-request
  "Validate request against provider capabilities"
  [provider request]
  (when (and (:stream request) (not (supports-streaming? provider)))
    (throw (ex-info "Provider doesn't support streaming" 
                    {:provider (provider-name provider)
                     :request request})))
  
  (when (and (or (:tools request) (:functions request)) 
             (not (supports-function-calling? provider)))
    (throw (ex-info "Provider doesn't support function calling"
                    {:provider (provider-name provider)
                     :request request})))
  
  (when-not (schemas/valid-request? request)
    (throw (ex-info "Invalid request format"
                    {:provider (provider-name provider)
                     :request request
                     :errors (schemas/explain-request request)}))))

;; ============================================================================
;; Model String Parsing
;; ============================================================================

(defn extract-provider-name
  "Extract provider name from model string (e.g., 'openai/gpt-4' -> 'openai')"
  [model]
  (if (string? model)
    (let [parts (str/split model #"/")]
      (if (> (count parts) 1)
        (first parts)
        "openai")) ; Default to openai for bare model names
    (str model)))

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
  "Parse model string into provider and model components"
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
;; Error Handling
;; ============================================================================

(defn provider-error
  "Create a provider-specific error"
  [provider message & {:keys [status code data]}]
  (ex-info message
           (cond-> {:provider (if (string? provider) provider (provider-name provider))
                    :type :provider-error}
             status (assoc :status status)
             code (assoc :code code)
             data (assoc :data data))))

(defn rate-limit-error
  "Create a rate limit error"
  [provider & {:keys [retry-after]}]
  (provider-error provider "Rate limit exceeded"
                  :status 429
                  :code :rate-limit
                  :data {:retry-after retry-after}))

(defn authentication-error
  "Create an authentication error"
  [provider]
  (provider-error provider "Authentication failed"
                  :status 401
                  :code :authentication))

(defn model-not-found-error
  "Create a model not found error"
  [provider model]
  (provider-error provider (str "Model not found: " model)
                  :status 404
                  :code :model-not-found
                  :data {:model model}))

(defn quota-exceeded-error
  "Create a quota exceeded error"
  [provider]
  (provider-error provider "Quota exceeded"
                  :status 429
                  :code :quota-exceeded))

;; ============================================================================
;; Provider Registry
;; ============================================================================

(def ^:private provider-registry (atom {}))

(defn register-provider!
  "Register a provider in the global registry"
  [provider-name provider-factory]
  (swap! provider-registry assoc provider-name provider-factory)
  (log/info "Registered provider" {:provider provider-name}))

(defn get-provider-factory
  "Get provider factory by name"
  [provider-name]
  (get @provider-registry provider-name))

(defn list-registered-providers
  "List all registered provider names"
  []
  (keys @provider-registry))

(defn create-provider
  "Create provider instance from registry"
  [provider-name config]
  (if-let [factory (get-provider-factory provider-name)]
    (factory config)
    (throw (ex-info "Unknown provider" {:provider provider-name
                                       :available (list-registered-providers)}))))

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
    (throw (ex-info "Invalid provider configuration"
                    {:config config
                     :errors (schemas/explain-config config)})))
  config)

;; ============================================================================
;; Provider Utilities
;; ============================================================================

(defn provider-supports-model?
  "Check if provider supports a specific model"
  [provider model]
  ;; This is a basic implementation - providers can override this
  (let [provider-name (provider-name provider)
        model-provider (extract-provider-name model)]
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
  [provider model prompt-tokens completion-tokens]
  (try
    (let [cost-per-token (get-cost-per-token provider model)]
      (+ (* prompt-tokens (:input cost-per-token 0))
         (* completion-tokens (:output cost-per-token 0))))
    (catch Exception e
      (log/warn "Error calculating cost" {:provider (provider-name provider) :model model :error (.getMessage e)})
      0.0)))

;; ============================================================================
;; Provider Health and Status
;; ============================================================================

(defn provider-status
  "Get comprehensive provider status"
  [provider]
  {:name (provider-name provider)
   :supports-streaming (supports-streaming? provider)
   :supports-function-calling (supports-function-calling? provider)
   :rate-limits (get-rate-limits provider)})

(defn log-provider-status
  "Log provider status for debugging"
  [provider]
  (log/info "Provider status" (provider-status provider)))

;; ============================================================================
;; Provider Testing Utilities
;; ============================================================================

(defn test-provider
  "Test provider with a simple request"
  [provider thread-pools telemetry]
  (let [test-request {:model "test"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 1}]
    (try
      (validate-request provider test-request)
      (let [transformed (transform-request provider test-request)
            response-future (make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (transform-response provider response)]
        {:success true
         :provider (provider-name provider)
         :response standard-response})
      (catch Exception e
        {:success false
         :provider (provider-name provider)
         :error (.getMessage e)}))))

;; ============================================================================
;; Provider Comparison Utilities
;; ============================================================================

(defn compare-providers
  "Compare multiple providers on various capabilities"
  [providers]
  (map (fn [provider]
         (assoc (provider-status provider)
                :health-check-available (try
                                        (some? (health-check provider nil))
                                        (catch Exception _ false))))
       providers))

(defn find-providers-for-model
  "Find all providers that support a given model"
  [providers model]
  (filter #(provider-supports-model? % model) providers))
