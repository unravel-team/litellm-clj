(ns litellm.router
  "Router-based configuration API for LiteLLM"
  (:require [clojure.tools.logging :as log]
            [litellm.core :as core]
            [litellm.config :as config]))

;; ============================================================================
;; Re-export Configuration Router Functions
;; ============================================================================

(defn register!
  "Register a provider configuration with a keyword name.
  
  Configurations are stored in a registry and can be referenced by name in [[completion]] calls.
  
  **Configuration Types:**
  - **Simple:** `{:provider :openai :model \"gpt-4\" :config {...}}`
  - **With router:** `{:router router-fn :configs {...}}`
  
  **Examples:**
  
  ```clojure
  ;; Simple configuration
  (register! :fast 
    {:provider :openai 
     :model \"gpt-4o-mini\" 
     :config {:api-key \"sk-...\"}})
  
  ;; Dynamic router configuration
  (register! :adaptive
    {:router (fn [{:keys [priority]}]
               (if (= priority :high)
                 {:provider :anthropic :model \"claude-3-opus-20240229\"}
                 {:provider :openai :model \"gpt-4o-mini\"}))
     :configs {:openai {:api-key \"sk-...\"}
               :anthropic {:api-key \"sk-ant-...\"}}})
  ```
  
  **See also:** [[completion]], [[unregister!]], [[list-configs]]"
  [config-name config-map]
  (config/register! config-name config-map))

(defn unregister!
  "Remove a configuration from the router"
  [config-name]
  (config/unregister! config-name))

(defn list-configs
  "List all registered configuration names"
  []
  (config/list-configs))

(defn get-config
  "Get a registered configuration by name"
  [config-name]
  (config/get-config config-name))

(defn clear-router!
  "Clear all registered configurations (useful for testing)"
  []
  (config/clear-registry!))

;; ============================================================================
;; Unified Completion API Using Router
;; ============================================================================

(defn completion
  "Completion function using registered configurations.
  
  First argument is a keyword referring to a registered config name.
  
  Examples:
    ;; Using registered config
    (completion :fast {:messages [{:role :user :content \"Hello\"}]})
    
    ;; With additional options
    (completion :smart 
                {:messages [{:role :user :content \"Hello\"}]
                 :max-tokens 100})"
  [config-name request-map]
  (let [resolved (config/resolve-config config-name request-map)
        provider (:provider resolved)
        model (:model resolved)
        provider-config (:config resolved)
        full-request (merge request-map
                           (when provider-config
                             (select-keys provider-config [:api-key :api-base])))]
    (core/completion provider model full-request provider-config)))

(defn chat
  "Simple chat completion using registered configuration.
  
  Examples:
    (chat :fast \"What is 2+2?\")
    (chat :smart \"Explain quantum physics\" :system-prompt \"You are a physicist\")"
  [config-name message & {:keys [system-prompt] :as opts}]
  (let [messages (if system-prompt
                   [{:role :system :content system-prompt}
                    {:role :user :content message}]
                   [{:role :user :content message}])
        request {:messages messages}]
    (completion config-name request)))

;; ============================================================================
;; Convenience Setup Functions
;; ============================================================================

(defn setup-openai!
  "Quick setup for OpenAI with optional custom config name"
  [& {:keys [config-name api-key model]
      :or {config-name :openai
           model "gpt-4o-mini"}}]
  (let [key (or api-key (System/getenv "OPENAI_API_KEY"))]
    (when-not key
      (throw (ex-info "OpenAI API key not provided and OPENAI_API_KEY env var not set" {})))
    (register! config-name
               {:provider :openai
                :model model
                :config {:api-key key}})))

(defn setup-anthropic!
  "Quick setup for Anthropic with optional custom config name"
  [& {:keys [config-name api-key model]
      :or {config-name :anthropic
           model "claude-3-sonnet-20240229"}}]
  (let [key (or api-key (System/getenv "ANTHROPIC_API_KEY"))]
    (when-not key
      (throw (ex-info "Anthropic API key not provided and ANTHROPIC_API_KEY env var not set" {})))
    (register! config-name
               {:provider :anthropic
                :model model
                :config {:api-key key}})))

(defn setup-gemini!
  "Quick setup for Gemini with optional custom config name"
  [& {:keys [config-name api-key model]
      :or {config-name :gemini
           model "gemini-pro"}}]
  (let [key (or api-key (System/getenv "GEMINI_API_KEY"))]
    (when-not key
      (throw (ex-info "Gemini API key not provided and GEMINI_API_KEY env var not set" {})))
    (register! config-name
               {:provider :gemini
                :model model
                :config {:api-key key}})))

(defn setup-mistral!
  "Quick setup for Mistral with optional custom config name"
  [& {:keys [config-name api-key model]
      :or {config-name :mistral
           model "mistral-medium"}}]
  (let [key (or api-key (System/getenv "MISTRAL_API_KEY"))]
    (when-not key
      (throw (ex-info "Mistral API key not provided and MISTRAL_API_KEY env var not set" {})))
    (register! config-name
               {:provider :mistral
                :model model
                :config {:api-key key}})))

(defn setup-ollama!
  "Quick setup for Ollama with optional custom config name"
  [& {:keys [config-name api-base model]
      :or {config-name :ollama
           api-base "http://localhost:11434"
           model "llama3"}}]
  (register! config-name
             {:provider :ollama
              :model model
              :config {:api-base api-base}}))

(defn setup-openrouter!
  "Quick setup for OpenRouter with optional custom config name"
  [& {:keys [config-name api-key model]
      :or {config-name :openrouter
           model "openai/gpt-4"}}]
  (let [key (or api-key (System/getenv "OPENROUTER_API_KEY"))]
    (when-not key
      (throw (ex-info "OpenRouter API key not provided and OPENROUTER_API_KEY env var not set" {})))
    (register! config-name
               {:provider :openrouter
                :model model
                :config {:api-key key}})))

(defn setup-azure!
  "Quick setup for Azure OpenAI with required configuration.

  Required config:
  - :api-base - Azure resource endpoint (e.g., https://my-resource.openai.azure.com)
  - :deployment - Deployment name
  - :api-key - Azure OpenAI API key (or AZURE_OPENAI_API_KEY env var)

  Optional:
  - :config-name - Config name to register (default: :azure)
  - :api-version - API version (default: 2024-10-21)"
  [& {:keys [config-name api-key api-base deployment api-version]
      :or {config-name :azure
           api-version "2024-10-21"}}]
  (let [key (or api-key (System/getenv "AZURE_OPENAI_API_KEY"))
        base (or api-base (System/getenv "AZURE_OPENAI_API_BASE"))
        deploy (or deployment (System/getenv "AZURE_OPENAI_DEPLOYMENT"))]
    (when-not key
      (throw (ex-info "Azure OpenAI API key not provided and AZURE_OPENAI_API_KEY env var not set" {})))
    (when-not base
      (throw (ex-info "Azure OpenAI API base not provided and AZURE_OPENAI_API_BASE env var not set" {})))
    (when-not deploy
      (throw (ex-info "Azure OpenAI deployment not provided and AZURE_OPENAI_DEPLOYMENT env var not set" {})))
    (register! config-name
               {:provider :azure
                :model deploy  ; For Azure, model is the deployment name
                :config {:api-key key
                         :api-base base
                         :deployment deploy
                         :api-version api-version}})))

(defn quick-setup!
  "Quick setup for common providers using environment variables

  Sets up:
  - :openai if OPENAI_API_KEY is set
  - :anthropic if ANTHROPIC_API_KEY is set
  - :gemini if GEMINI_API_KEY is set
  - :mistral if MISTRAL_API_KEY is set
  - :azure if AZURE_OPENAI_API_KEY, AZURE_OPENAI_API_BASE, and AZURE_OPENAI_DEPLOYMENT are set
  - :ollama (always, defaults to localhost)"
  []
  (when (System/getenv "OPENAI_API_KEY")
    (setup-openai!))

  (when (System/getenv "ANTHROPIC_API_KEY")
    (setup-anthropic!))

  (when (System/getenv "GEMINI_API_KEY")
    (setup-gemini!))

  (when (System/getenv "MISTRAL_API_KEY")
    (setup-mistral!))

  (when (System/getenv "OPENROUTER_API_KEY")
    (setup-openrouter!))

  (when (and (System/getenv "AZURE_OPENAI_API_KEY")
             (System/getenv "AZURE_OPENAI_API_BASE")
             (System/getenv "AZURE_OPENAI_DEPLOYMENT"))
    (setup-azure!))

  (setup-ollama!)

  (log/info "Quick setup complete. Available configs:" (list-configs)))

;; ============================================================================
;; Helper for Creating Routers
;; ============================================================================

(defn create-router
  "Helper to create a router configuration with multiple providers
  
  Example:
    (create-router
      (fn [{:keys [priority]}]
        (if (= priority :high)
          {:provider :anthropic :model \"claude-3-opus\"}
          {:provider :openai :model \"gpt-4o-mini\"}))
      {:openai {:api-key \"sk-...\"}
       :anthropic {:api-key \"sk-...\"}})"
  [router-fn provider-configs]
  {:router router-fn
   :configs provider-configs})

;; ============================================================================
;; Provider Discovery (Re-export from core)
;; ============================================================================

(defn list-providers
  "List all available providers"
  []
  (core/list-providers))

(defn provider-available?
  "Check if a provider is available"
  [provider-name]
  (core/provider-available? provider-name))

(defn provider-info
  "Get information about a provider"
  [provider-name]
  (core/provider-info provider-name))

;; ============================================================================
;; Validation Helpers (Re-export from core)
;; ============================================================================

(defn validate-request
  "Validate a request against provider capabilities"
  [provider-name request]
  (core/validate-request provider-name request))

(defn supports-streaming?
  "Check if provider supports streaming"
  [provider-name]
  (core/supports-streaming? provider-name))

(defn supports-function-calling?
  "Check if provider supports function calling"
  [provider-name]
  (core/supports-function-calling? provider-name))

;; ============================================================================
;; Response Utilities (Re-export from core)
;; ============================================================================

(defn extract-content
  "Extract content from completion response"
  [response]
  (core/extract-content response))

(defn extract-message
  "Extract message from completion response"
  [response]
  (core/extract-message response))

(defn extract-usage
  "Extract usage information from response"
  [response]
  (core/extract-usage response))

;; ============================================================================
;; Cost Estimation (Re-export from core)
;; ============================================================================

(defn estimate-tokens
  "Estimate token count for text"
  [text]
  (core/estimate-tokens text))

(defn estimate-request-tokens
  "Estimate token count for a request"
  [request]
  (core/estimate-request-tokens request))

(defn calculate-cost
  "Calculate cost for a request/response"
  [provider-name model prompt-tokens completion-tokens]
  (core/calculate-cost provider-name model prompt-tokens completion-tokens))
