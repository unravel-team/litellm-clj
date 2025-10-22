(ns litellm.core
  "Core API for LiteLLM - Direct provider calls with model names as-is"
  (:require [clojure.tools.logging :as log]
            [litellm.providers.core :as providers]
            [litellm.providers.openai]    ; Load to register provider
            [litellm.providers.anthropic] ; Load to register provider
            [litellm.providers.openrouter] ; Load to register provider
            [litellm.threadpool :as threadpool]
            [hato.client :as http]
            [cheshire.core :as json]))

;; ============================================================================
;; Provider Discovery
;; ============================================================================

(defn list-providers
  "List all available providers (registered via multimethods)"
  []
  (providers/list-available-providers))

(defn provider-available?
  "Check if a provider is available"
  [provider-name]
  (providers/provider-available? provider-name))

(defn provider-info
  "Get information about a provider"
  [provider-name]
  (providers/provider-status provider-name))

;; ============================================================================
;; Direct HTTP Request Helpers
;; ============================================================================

(defn- make-sync-http-request
  "Make a synchronous HTTP request without thread pools"
  [provider-name transformed-request]
  (let [url (:url transformed-request)
        headers (:headers transformed-request)
        body (:body transformed-request)]
    (try
      (let [response (http/post url
                               {:headers headers
                                :body (json/generate-string body)
                                :as :json
                                :throw-exceptions? false})]
        (if (< (:status response) 400)
          (:body response)
          (throw (ex-info "API request failed"
                         {:status (:status response)
                          :body (:body response)
                          :provider provider-name}))))
      (catch Exception e
        (throw (ex-info "HTTP request failed"
                       {:provider provider-name
                        :error (.getMessage e)}
                       e))))))

(def ^:private minimal-thread-pools
  "Minimal thread pools for streaming support in core"
  (delay (threadpool/create-thread-pools {:api-calls {:pool-size 5}})))

;; ============================================================================
;; Core Completion API
;; ============================================================================

(defn completion
  "Direct completion function - accepts provider keyword and model name as-is.
  
  Supports both streaming and non-streaming requests.
  For streaming requests, returns a core.async channel.
  
  Examples:
    ;; Non-streaming
    (completion :openai \"gpt-4\" 
                {:messages [{:role :user :content \"Hello\"}]
                 :api-key \"sk-...\"})
    
    ;; Streaming
    (completion :openai \"gpt-4\"
                {:messages [{:role :user :content \"Hello\"}]
                 :stream true
                 :api-key \"sk-...\"})
    
    (completion :anthropic \"claude-3-sonnet-20240229\"
                {:messages [{:role :user :content \"Hello\"}]
                 :api-key \"sk-ant-...\"})"
  ([provider-name model request-map]
   (completion provider-name model request-map {}))
  
  ([provider-name model request-map config]
   ;; Validate provider exists
   (when-not (provider-available? provider-name)
     (throw (ex-info "Provider not available"
                    {:provider provider-name
                     :available-providers (list-providers)})))
   
   ;; Build full request with model
   (let [request (assoc request-map :model model)]
     
     ;; Validate request
     (providers/validate-request provider-name request)
     
     ;; Check if streaming
     (if (:stream request)
       ;; Streaming request - return channel
       (let [transformed-request (providers/transform-request provider-name request config)]
         (providers/make-streaming-request provider-name transformed-request @minimal-thread-pools config))
       
       ;; Non-streaming request - use the provider's make-request
       (let [transformed-request (providers/transform-request provider-name request config)
             response-future (providers/make-request provider-name transformed-request @minimal-thread-pools nil config)
             response @response-future]  ; Block and wait for response
         
         ;; Transform response
         (providers/transform-response provider-name response))))))

(defn chat
  "Simple chat completion function.
  
  Example:
    (chat :openai \"gpt-4\" \"What is 2+2?\" 
          {:api-key \"sk-...\" :system-prompt \"You are a math tutor\"})"
  [provider-name model message & {:keys [system-prompt] :as config}]
  (let [messages (if system-prompt
                   [{:role :system :content system-prompt}
                    {:role :user :content message}]
                   [{:role :user :content message}])
        request {:messages messages}]
    (completion provider-name model request (dissoc config :system-prompt))))

;; ============================================================================
;; Provider-Specific Convenience Functions
;; ============================================================================

(defn openai-completion
  "Direct OpenAI completion"
  [model request-map & {:as config}]
  (completion :openai model request-map config))

(defn anthropic-completion
  "Direct Anthropic completion"
  [model request-map & {:as config}]
  (completion :anthropic model request-map config))

(defn gemini-completion
  "Direct Gemini completion"
  [model request-map & {:as config}]
  (completion :gemini model request-map config))

(defn mistral-completion
  "Direct Mistral completion"
  [model request-map & {:as config}]
  (completion :mistral model request-map config))

(defn ollama-completion
  "Direct Ollama completion"
  [model request-map & {:as config}]
  (completion :ollama model request-map config))

(defn openrouter-completion
  "Direct OpenRouter completion"
  [model request-map & {:as config}]
  (completion :openrouter model request-map config))

;; ============================================================================
;; Provider Validation
;; ============================================================================

(defn validate-request
  "Validate a request against provider capabilities"
  [provider-name request]
  (providers/validate-request provider-name request))

(defn supports-streaming?
  "Check if provider supports streaming"
  [provider-name]
  (providers/supports-streaming? provider-name))

(defn supports-function-calling?
  "Check if provider supports function calling"
  [provider-name]
  (providers/supports-function-calling? provider-name))

;; ============================================================================
;; Cost Estimation
;; ============================================================================

(defn estimate-tokens
  "Estimate token count for text"
  [text]
  (providers/estimate-tokens text))

(defn estimate-request-tokens
  "Estimate token count for a request"
  [request]
  (providers/estimate-request-tokens request))

(defn calculate-cost
  "Calculate cost for a request/response"
  [provider-name model prompt-tokens completion-tokens]
  (providers/calculate-cost provider-name model prompt-tokens completion-tokens))

;; ============================================================================
;; Response Utilities
;; ============================================================================

(defn extract-content
  "Extract content from completion response"
  [response]
  (-> response :choices first :message :content))

(defn extract-message
  "Extract message from completion response"
  [response]
  (-> response :choices first :message))

(defn extract-usage
  "Extract usage information from response"
  [response]
  (:usage response))

;; ============================================================================
;; Error Handling Utilities
;; ============================================================================

(defn with-error-handling
  "Execute function with comprehensive error handling"
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (case (:type data)
          :provider-error (log/error "Provider error" data)
          :rate-limit (log/warn "Rate limit exceeded" data)
          :authentication (log/error "Authentication failed" data)
          (log/error "Unknown error" data))
        (throw e)))
    (catch Exception e
      (log/error "Unexpected error" e)
      (throw e))))
