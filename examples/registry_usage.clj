(ns examples.registry-usage
  "Example usage of litellm.registry - configuration-based API"
  (:require [litellm.registry :as registry]))

;; ============================================================================
;; litellm.registry - Configuration-based API with named configs
;; ============================================================================

;; Registry allows you to:
;; - Register named configurations
;; - Use routing functions to dynamically select providers
;; - Quick setup with environment variables

(defn basic-registry-example []
  (println "\n=== Basic Registry Example ===")
  
  ;; Register a simple configuration
  (registry/register! :fast
                     {:provider :openai
                      :model "gpt-4o-mini"
                      :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; Use the registered config
  (let [response (registry/completion :fast 
                                     {:messages [{:role :user :content "What is 2+2?"}]
                                      :max-tokens 50})]
    (println "Response:" (registry/extract-content response)))
  
  ;; List registered configs
  (println "Registered configs:" (registry/list-configs)))

(defn router-example []
  (println "\n=== Router Example ===")
  
  ;; Register a configuration with dynamic routing
  (registry/register! :smart
                     {:router (fn [request]
                               ;; Route based on message count
                               (if (> (count (:messages request)) 5)
                                 {:provider :anthropic :model "claude-3-opus-20240229"}
                                 {:provider :openai :model "gpt-4o-mini"}))
                      :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
                               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})
  
  ;; Short conversation - will use OpenAI
  (let [response (registry/completion :smart
                                     {:messages [{:role :user :content "Hi!"}]
                                      :max-tokens 50})]
    (println "Short conversation (OpenAI):" (registry/extract-content response)))
  
  ;; Long conversation - will use Anthropic (if available)
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [response (registry/completion :smart
                                       {:messages [{:role :user :content "1"}
                                                  {:role :assistant :content "a"}
                                                  {:role :user :content "2"}
                                                  {:role :assistant :content "b"}
                                                  {:role :user :content "3"}
                                                  {:role :assistant :content "c"}
                                                  {:role :user :content "Tell me about routing"}]
                                        :max-tokens 100})]
      (println "Long conversation (Anthropic):" (registry/extract-content response)))))

(defn quick-setup-example []
  (println "\n=== Quick Setup Example ===")
  
  ;; Clear any existing configs
  (registry/clear-registry!)
  
  ;; Quick setup automatically registers configs for available providers
  (registry/quick-setup!)
  
  (println "Auto-configured providers:" (registry/list-configs))
  
  ;; Now you can use the auto-configured providers
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (registry/completion :openai
                                       {:messages [{:role :user :content "Hello!"}]
                                        :max-tokens 30})]
      (println "OpenAI response:" (registry/extract-content response)))))

(defn manual-setup-example []
  (println "\n=== Manual Setup Example ===")
  
  ;; Set up individual providers with custom names
  (registry/setup-openai! :config-name :my-fast-model
                         :model "gpt-4o-mini")
  
  (registry/setup-openai! :config-name :my-smart-model
                         :model "gpt-4")
  
  (println "Configured models:" (registry/list-configs))
  
  ;; Use the custom configs
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (registry/chat :my-fast-model "What is the weather like?")]
      (println "Fast model response:" (registry/extract-content response)))))

(defn custom-router-example []
  (println "\n=== Custom Router Example ===")
  
  ;; Create a router based on priority
  (let [router-config (registry/create-router
                       (fn [{:keys [priority]}]
                         (case priority
                           :high {:provider :anthropic :model "claude-3-opus-20240229"}
                           :low {:provider :openai :model "gpt-4o-mini"}
                           {:provider :openai :model "gpt-4"}))
                       {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
                        :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}})]
    
    (registry/register! :priority-router router-config)
    
    ;; High priority request (uses Anthropic)
    (when (System/getenv "ANTHROPIC_API_KEY")
      (let [response (registry/completion :priority-router
                                         {:messages [{:role :user :content "Important question"}]
                                          :max-tokens 50
                                          :priority :high})]
        (println "High priority response (Anthropic):" (registry/extract-content response))))
    
    ;; Low priority request (uses OpenAI mini)
    (when (System/getenv "OPENAI_API_KEY")
      (let [response (registry/completion :priority-router
                                         {:messages [{:role :user :content "Simple question"}]
                                          :max-tokens 50
                                          :priority :low})]
        (println "Low priority response (OpenAI mini):" (registry/extract-content response))))))

(defn -main []
  (println "litellm.registry Examples")
  (println "=========================")
  
  (when (System/getenv "OPENAI_API_KEY")
    (basic-registry-example)
    (quick-setup-example)
    (manual-setup-example))
  
  (when (and (System/getenv "OPENAI_API_KEY")
            (System/getenv "ANTHROPIC_API_KEY"))
    (router-example)
    (custom-router-example)))

(comment
  ;; Run examples
  (-main)
  
  ;; Individual examples
  (basic-registry-example)
  (router-example)
  (quick-setup-example)
  (manual-setup-example)
  (custom-router-example))
