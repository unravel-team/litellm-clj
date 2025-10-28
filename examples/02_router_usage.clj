(ns examples.router-usage
  "Example usage of litellm.router - configuration-based API"
  (:require [litellm.router :as router]))

;; ============================================================================
;; litellm.router - Configuration-based API with named configs
;; ============================================================================

;; Router allows you to:
;; - Register named configurations
;; - Use routing functions to dynamically select providers
;; - Quick setup with environment variables

(defn basic-router-example []
  (println "\n=== Basic Router Example ===")
  
  ;; Register a simple configuration
  (router/register! :fast
                     {:provider :openai
                      :model "gpt-4o-mini"
                      :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; Use the registered config
  (let [response (router/completion :fast 
                                     {:messages [{:role :user :content "What is 2+2?"}]
                                      :max-tokens 50})]
    (println "Response:" (router/extract-content response)))
  
  ;; List registered configs
  (println "Registered configs:" (router/list-configs)))

(defn dynamic-routing-example []
  (println "\n=== Dynamic Routing Example ===")
  
  ;; Register a configuration with dynamic routing
  (router/register! :smart
                     {:router (fn [request]
                               ;; Route based on message count
                               (if (> (count (:messages request)) 5)
                                 {:provider :anthropic :model "claude-3-opus-20240229"}
                                 {:provider :openai :model "gpt-4o-mini"}))
                      :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
                               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})
  
  ;; Short conversation - will use OpenAI
  (let [response (router/completion :smart
                                     {:messages [{:role :user :content "Hi!"}]
                                      :max-tokens 50})]
    (println "Short conversation (OpenAI):" (router/extract-content response)))
  
  ;; Long conversation - will use Anthropic (if available)
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [response (router/completion :smart
                                       {:messages [{:role :user :content "1"}
                                                  {:role :assistant :content "a"}
                                                  {:role :user :content "2"}
                                                  {:role :assistant :content "b"}
                                                  {:role :user :content "3"}
                                                  {:role :assistant :content "c"}
                                                  {:role :user :content "Tell me about routing"}]
                                        :max-tokens 100})]
      (println "Long conversation (Anthropic):" (router/extract-content response)))))

(defn quick-setup-example []
  (println "\n=== Quick Setup Example ===")
  
  ;; Clear any existing configs
  (router/clear-router!)
  
  ;; Quick setup automatically registers configs for available providers
  (router/quick-setup!)
  
  (println "Auto-configured providers:" (router/list-configs))
  
  ;; Now you can use the auto-configured providers
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (router/completion :openai
                                       {:messages [{:role :user :content "Hello!"}]
                                        :max-tokens 30})]
      (println "OpenAI response:" (router/extract-content response)))))

(defn manual-setup-example []
  (println "\n=== Manual Setup Example ===")
  
  ;; Set up individual providers with custom names
  (router/setup-openai! :config-name :my-fast-model
                         :model "gpt-4o-mini")
  
  (router/setup-openai! :config-name :my-smart-model
                         :model "gpt-4")
  
  (println "Configured models:" (router/list-configs))
  
  ;; Use the custom configs
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (router/chat :my-fast-model "What is the weather like?")]
      (println "Fast model response:" (router/extract-content response)))))

(defn custom-router-example []
  (println "\n=== Custom Router Example ===")
  
  ;; Create a router based on priority
  (let [router-config (router/create-router
                       (fn [{:keys [priority]}]
                         (case priority
                           :high {:provider :anthropic :model "claude-3-opus-20240229"}
                           :low {:provider :openai :model "gpt-4o-mini"}
                           {:provider :openai :model "gpt-4"}))
                       {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
                        :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}})]
    
    (router/register! :priority-router router-config)
    
    ;; High priority request (uses Anthropic)
    (when (System/getenv "ANTHROPIC_API_KEY")
      (let [response (router/completion :priority-router
                                         {:messages [{:role :user :content "Important question"}]
                                          :max-tokens 50
                                          :priority :high})]
        (println "High priority response (Anthropic):" (router/extract-content response))))
    
    ;; Low priority request (uses OpenAI mini)
    (when (System/getenv "OPENAI_API_KEY")
      (let [response (router/completion :priority-router
                                         {:messages [{:role :user :content "Simple question"}]
                                          :max-tokens 50
                                          :priority :low})]
        (println "Low priority response (OpenAI mini):" (router/extract-content response))))))

(defn -main []
  (println "litellm.router Examples")
  (println "=========================")
  
  (when (System/getenv "OPENAI_API_KEY")
    (basic-router-example)
    (quick-setup-example)
    (manual-setup-example))
  
  (when (and (System/getenv "OPENAI_API_KEY")
            (System/getenv "ANTHROPIC_API_KEY"))
    (dynamic-routing-example)
    (custom-router-example)))

(comment
  ;; Run examples
  (-main)
  
  ;; Individual examples
  (basic-router-example)
  (dynamic-routing-example)
  (quick-setup-example)
  (manual-setup-example)
  (custom-router-example))
