(ns litellm.core
  "Core API for LiteLLM - Direct provider calls with model names as-is"
  (:require [clojure.tools.logging :as log]
            [litellm.errors :as errors]
            [litellm.providers.core :as providers]
            [litellm.providers.openai]    ; Load to register provider
            [litellm.providers.anthropic] ; Load to register provider
            [litellm.providers.gemini]    ; Load to register provider
            [litellm.providers.mistral]   ; Load to register provider
            [litellm.providers.ollama]    ; Load to register provider
            [litellm.providers.openrouter] ; Load to register provider
            ))

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
;; Core Completion API
;; ============================================================================

(defn completion
  "Direct completion function - accepts `provider` keyword and `model` name as-is.
  
  Supports both streaming and non-streaming requests.
  For streaming requests, returns a `core.async` channel.
  
  **Parameters:**
  - `provider` - Provider keyword (`:openai`, `:anthropic`, `:gemini`, `:mistral`, `:ollama`, `:openrouter`)
  - `model` - Model name string (e.g., `\"gpt-4\"`, `\"claude-3-opus-20240229\"`)
  - `request-map` - Request with `:messages`, `:temperature`, `:max-tokens`, etc.
  - `config` - Optional config with `:api-key`, `:api-base`, `:timeout`
  
  **Returns:**
  - Non-streaming: Response map with `:choices`, `:usage`, etc.
  - Streaming: `core.async` channel with response chunks
  
  **Examples:**
  
  ```clojure
  ;; Non-streaming completion
  (completion :openai \"gpt-4\" 
              {:messages [{:role :user :content \"Hello\"}]}
              {:api-key \"sk-...\"})
  
  ;; Streaming completion (returns channel)
  (completion :openai \"gpt-4\"
              {:messages [{:role :user :content \"Hello\"}]
               :stream true}
              {:api-key \"sk-...\"})
  
  ;; Anthropic Claude
  (completion :anthropic \"claude-3-sonnet-20240229\"
              {:messages [{:role :user :content \"Hello\"}]}
              {:api-key \"sk-ant-...\"})
  ```
  
  **See also:** [[chat]], [[extract-content]], [[extract-message]]"
  ([provider-name model request-map]
   (completion provider-name model request-map {}))
  
  ([provider-name model request-map config]
   ;; Validate provider exists
   (when-not (provider-available? provider-name)
     (throw (errors/provider-not-found 
              (name provider-name)
              :available-providers (list-providers))))
   
   ;; Build full request with model
   (let [request (assoc request-map :model model)]
     
     ;; Validate request
     (providers/validate-request provider-name request)
     
     ;; Check if streaming
     (if (:stream request)
       ;; Streaming request - return channel
       (let [;; Merge API key and other request params into config
             merged-config (merge config (select-keys request [:api-key :api-base :timeout]))
             transformed-request (providers/transform-request provider-name request merged-config)]
         (providers/make-streaming-request provider-name transformed-request nil merged-config))
       
       ;; Non-streaming request - use the provider's make-request
       (let [;; Merge API key and other request params into config
             merged-config (merge config (select-keys request [:api-key :api-base :timeout]))
             transformed-request (providers/transform-request provider-name request merged-config)
             response-future (providers/make-request provider-name transformed-request nil nil merged-config)
             response @response-future]  ; Block and wait for response
         ;; Transform response
         (providers/transform-response provider-name response))))))

(defn chat
  "Simple chat completion function for single user messages.
  
  Convenience wrapper around [[completion]] for simple question-answer interactions.
  
  **Parameters:**
  - `provider-name` - Provider keyword (`:openai`, `:anthropic`, etc.)
  - `model` - Model name string
  - `message` - User message string
  - `config` - Optional keyword args including `:system-prompt`, `:api-key`, etc.
  
  **Examples:**
  
  ```clojure
  ;; Simple question
  (chat :openai \"gpt-4\" \"What is 2+2?\" 
        :api-key \"sk-...\")
  
  ;; With system prompt
  (chat :openai \"gpt-4\" \"Explain quantum physics\"
        :api-key \"sk-...\"
        :system-prompt \"You are a physics professor\")
  ```
  
  **See also:** [[completion]], [[extract-content]]"
  [provider-name model message & {:keys [system-prompt] :as config}]
  (let [messages (if system-prompt
                   [{:role :system :content system-prompt}
                    {:role :user :content message}]
                   [{:role :user :content message}])
        request {:messages messages}]
    (completion provider-name model request (dissoc config :system-prompt))))

;; ============================================================================
;; Core Embedding API
;; ============================================================================

(defn embedding
  "Generate embeddings for text input.
  
  **Parameters:**
  - `provider` - Provider keyword (`:openai`, `:mistral`, `:gemini`)
  - `model` - Model name string (e.g., `\"text-embedding-3-small\"`, `\"mistral-embed\"`)
  - `request-map` - Request with `:input` (string or vector of strings)
  - `config` - Optional config with `:api-key`, `:api-base`, `:timeout`
  
  **Returns:**
  - Response map with `:data` (vector of embeddings), `:usage`, etc.
  
  **Examples:**
  
  ```clojure
  ;; Single text embedding
  (embedding :openai \"text-embedding-3-small\" 
             {:input \"Hello world\"}
             {:api-key \"sk-...\"})
  
  ;; Multiple texts
  (embedding :openai \"text-embedding-3-small\"
             {:input [\"Hello\" \"World\"]}
             {:api-key \"sk-...\"})
  
  ;; Mistral embeddings
  (embedding :mistral \"mistral-embed\"
             {:input \"Hello world\"}
             {:api-key \"...\"})
  
  ;; Gemini embeddings
  (embedding :gemini \"text-embedding-004\"
             {:input \"Hello world\"}
             {:api-key \"...\"})
  ```
  
  **See also:** [[openai-embedding]], [[mistral-embedding]], [[gemini-embedding]]"
  ([provider-name model request-map]
   (embedding provider-name model request-map {}))
  
  ([provider-name model request-map config]
   ;; Validate provider exists
   (when-not (provider-available? provider-name)
     (throw (errors/provider-not-found 
              (name provider-name)
              :available-providers (list-providers))))
   
   ;; Build full request with model
   (let [request (assoc request-map :model model)]
     
     ;; Validate embedding request
     (providers/validate-embedding-request provider-name request)
     
     ;; Merge API key and other request params into config
     (let [merged-config (merge config (select-keys request [:api-key :api-base :timeout]))
           transformed-request (providers/transform-embedding-request provider-name request merged-config)
           response-future (providers/make-embedding-request provider-name transformed-request nil nil merged-config)
           response @response-future]  ; Block and wait for response
       ;; Transform response
       (providers/transform-embedding-response provider-name response)))))

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

(defn openai-embedding
  "Direct OpenAI embedding"
  [model request-map & {:as config}]
  (embedding :openai model request-map config))

(defn mistral-embedding
  "Direct Mistral embedding"
  [model request-map & {:as config}]
  (embedding :mistral model request-map config))

(defn gemini-embedding
  "Direct Gemini embedding"
  [model request-map & {:as config}]
  (embedding :gemini model request-map config))

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
  "Extract text content from a completion response.
  
  Retrieves the generated text from the first choice in the response.
  
  **Example:**
  
  ```clojure
  (def response (completion :openai \"gpt-4\" {...}))
  (extract-content response)
  ;; => \"The generated text content...\"
  ```
  
  **See also:** [[extract-message]], [[extract-usage]]"
  [response]
  (-> response :choices first :message :content))

(defn extract-message
  "Extract the full message object from a completion response.
  
  Returns the complete message including `:content`, `:role`, and `:tool-calls` (if any).
  
  **Example:**
  
  ```clojure
  (def response (completion :openai \"gpt-4\" {...}))
  (extract-message response)
  ;; => {:role :assistant :content \"...\" :tool-calls [...]}
  ```
  
  **See also:** [[extract-content]], [[extract-usage]]"
  [response]
  (-> response :choices first :message))

(defn extract-usage
  "Extract token usage information from a completion response.
  
  Returns a map with `:prompt-tokens`, `:completion-tokens`, and `:total-tokens`.
  
  **Example:**
  
  ```clojure
  (def response (completion :openai \"gpt-4\" {...}))
  (extract-usage response)
  ;; => {:prompt-tokens 10 :completion-tokens 20 :total-tokens 30}
  ```
  
  **See also:** [[extract-content]], [[calculate-cost]]"
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
      (if (errors/litellm-error? e)
        (let [category (errors/get-error-category e)]
          (case category
            :provider-error (log/warn "Provider error" (errors/error-summary e))
            :client-error (log/error "Client error" (errors/error-summary e))
            :response-error (log/error "Response error" (errors/error-summary e))
            :system-error (log/error "System error" (errors/error-summary e))
            (log/error "Unknown error category" (errors/error-summary e)))
          (throw e))
        ;; Non-litellm error
        (do
          (log/error "Unexpected error" e)
          (throw e))))
    (catch Exception e
      (log/error "Unexpected error" e)
      (throw e))))
