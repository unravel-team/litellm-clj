(ns examples.core-usage
  "Example usage of litellm.core - direct provider calls"
  (:require [litellm.core :as core]))

;; ============================================================================
;; litellm.core - Direct provider calls with model names as-is
;; ============================================================================

;; No registry, no configuration management, just direct calls to providers.
;; Model names are passed exactly as providers expect them.

(defn openai-example []
  (println "\n=== OpenAI Example ===")
  (let [api-key (System/getenv "OPENAI_API_KEY")
        request {:messages [{:role :user :content "What is 2+2?"}]
                 :max-tokens 50}
        config {:api-key api-key}]
    
    ;; Direct call with provider keyword and model name
    (let [response (core/completion :openai "gpt-4o-mini" request config)]
      (println "Response:" (core/extract-content response)))))

(defn anthropic-example []
  (println "\n=== Anthropic Example ===")
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")
        request {:messages [{:role :user :content "What is the capital of France?"}]
                 :max-tokens 50}
        config {:api-key api-key}]
    
    (let [response (core/completion :anthropic "claude-3-sonnet-20240229" request config)]
      (println "Response:" (core/extract-content response)))))

(defn provider-specific-functions []
  (println "\n=== Provider-Specific Functions ===")
  
  ;; Use provider-specific convenience functions
  (let [api-key (System/getenv "OPENAI_API_KEY")
        request {:messages [{:role :user :content "Hello!"}]
                 :max-tokens 30}]
    
    (let [response (core/openai-completion "gpt-4o-mini" request :api-key api-key)]
      (println "OpenAI response:" (core/extract-content response)))))

(defn simple-chat-example []
  (println "\n=== Simple Chat Example ===")
  
  ;; Using the chat helper function
  (let [api-key (System/getenv "OPENAI_API_KEY")]
    (let [response (core/chat :openai 
                              "gpt-4o-mini" 
                              "Tell me a joke"
                              :api-key api-key
                              :system-prompt "You are a comedian")]
      (println "Chat response:" (core/extract-content response)))))

(defn provider-discovery []
  (println "\n=== Provider Discovery ===")
  
  ;; List available providers
  (println "Available providers:" (core/list-providers))
  
  ;; Check if specific provider is available
  (println "OpenAI available?" (core/provider-available? :openai))
  (println "Anthropic available?" (core/provider-available? :anthropic))
  
  ;; Get provider info
  (println "OpenAI info:" (core/provider-info :openai)))

(defn -main []
  (println "litellm.core Examples")
  (println "=====================")
  
  ;; Run examples that have API keys available
  (when (System/getenv "OPENAI_API_KEY")
    (openai-example)
    (provider-specific-functions)
    (simple-chat-example))
  
  (when (System/getenv "ANTHROPIC_API_KEY")
    (anthropic-example))
  
  (provider-discovery))

(comment
  ;; Run examples
  (-main)
  
  ;; Individual examples
  (openai-example)
  (anthropic-example)
  (provider-specific-functions)
  (simple-chat-example)
  (provider-discovery))
