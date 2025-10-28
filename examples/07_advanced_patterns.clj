(ns examples.advanced-patterns
  "Advanced usage patterns for LiteLLM router"
  (:require [litellm.router :as llm]
            [litellm.wrappers :as wrap]))

;; ============================================================================
;; A/B Testing Pattern
;; ============================================================================

(defn ab-testing-setup []
  "Setup for A/B testing between different models or providers"
  (llm/register! :ab-test
    {:router (fn [_]
               (if (< (rand) 0.5)
                 {:provider :openai :model "gpt-4o-mini"}
                 {:provider :anthropic :model "claude-3-haiku-20240307"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}}))

(defn ab-testing-example []
  "Example of A/B testing different models"
  (ab-testing-setup)
  
  ;; Make multiple requests - they'll randomly use different providers
  (dotimes [i 5]
    (let [response (llm/chat :ab-test "What is 2+2?")]
      (println (str "Request " (inc i) ": " (:model response) " : "(llm/extract-content response))))))

;; ============================================================================
;; Cost Optimization Pattern
;; ============================================================================

(defn cost-optimized-setup []
  "Route based on user tier to optimize costs"
  (llm/register! :cost-optimized
    {:router (fn [{:keys [user-tier]}]
               (case user-tier
                 :basic {:provider :openai :model "gpt-4o-mini"}
                 :premium {:provider :anthropic :model "claude-3-opus-20240229"}
                 {:provider :openai :model "gpt-4o-mini"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}}))

(defn cost-optimization-example []
  "Example of cost-optimized routing based on user tier"
  (cost-optimized-setup)
  
  ;; Free user gets local model
  (let [response (llm/completion :cost-optimized
                   {:messages [{:role :user :content "Hello"}]
                    :user-tier :basic
                    :max-tokens 50})]
    (println "Bsic tier: " (:model response) " : " (llm/extract-content response)))
  
  ;; Premium user gets best model
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [response (llm/completion :cost-optimized
                     {:messages [{:role :user :content "Hello"}]
                      :user-tier :premium
                      :max-tokens 50})]
      (println "Premium tier:"  (:model response) " : " (llm/extract-content response)))))

;; ============================================================================
;; Fallback Chain Pattern
;; ============================================================================

(defn fallback-chain-example []
  "Example of falling back through multiple providers"
  ;; Setup multiple configs
  (llm/register! :premium
    {:provider :anthropic
     :model "claude-3-opus-20240229"
     :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})
  
  (llm/register! :fast
    {:provider :openai
     :model "gpt-4o-mini"
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (llm/register! :local
    {:provider :ollama
     :model "llama3"
     :config {:api-base "http://localhost:11434"}})
  
  ;; Use wrapper to try configs in order
  (wrap/with-fallback [:premium :fast :local]
    {:messages [{:role :user :content "Hello"}]}
    llm/completion))

;; ============================================================================
;; Priority-Based Routing
;; ============================================================================

(defn priority-routing-setup []
  "Route based on request priority"
  (llm/register! :priority-router
    {:router (fn [{:keys [priority]}]
               (case priority
                 :urgent {:provider :anthropic :model "claude-3-opus-20240229"}
                 :high {:provider :openai :model "gpt-4"}
                 :normal {:provider :openai :model "gpt-4o-mini"}
                 :low {:provider :ollama :model "llama3"}
                 {:provider :openai :model "gpt-4o-mini"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}
               :ollama {:api-base "http://localhost:11434"}}}))

(defn priority-routing-example []
  "Example of routing based on priority"
  (priority-routing-setup)
  
  ;; Urgent request uses best model
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [response (llm/completion :priority-router
                     {:messages [{:role :user :content "Critical question"}]
                      :priority :urgent
                      :max-tokens 50})]
      (println "Urgent:" (llm/extract-content response))))
  
  ;; Normal request uses balanced model
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (llm/completion :priority-router
                     {:messages [{:role :user :content "Regular question"}]
                      :priority :normal
                      :max-tokens 50})]
      (println "Normal:" (llm/extract-content response)))))

;; ============================================================================
;; Content-Based Routing
;; ============================================================================

(defn content-routing-setup []
  "Route based on message content characteristics"
  (llm/register! :content-router
    {:router (fn [{:keys [messages]}]
               (let [content (-> messages last :content)
                     word-count (count (clojure.string/split content #"\s+"))]
                 (cond
                   ;; Long prompts need context window
                   (> word-count 1000)
                   {:provider :anthropic :model "claude-3-opus-20240229"}
                   
                   ;; Code-related queries
                   (re-find #"(?i)(code|function|debug|implement)" content)
                   {:provider :openai :model "gpt-4"}
                   
                   ;; Simple queries
                   :else
                   {:provider :openai :model "gpt-4o-mini"})))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}}))

(defn content-routing-example []
  "Example of routing based on content"
  (content-routing-setup)
  
  ;; Code question routes to gpt-4
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (llm/completion :content-router
                     {:messages [{:role :user 
                                  :content "How do I implement a binary search in Python?"}]
                      :max-tokens 100})]
      (println "Code question:" (llm/extract-content response))))
  
  ;; Simple question routes to mini
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (llm/completion :content-router
                     {:messages [{:role :user :content "What is 2+2?"}]
                      :max-tokens 50})]
      (println "Simple question:" (llm/extract-content response)))))

;; ============================================================================
;; Load Balancing Pattern
;; ============================================================================

(defn load-balancing-setup []
  "Distribute requests across multiple providers"
  (let [counter (atom 0)]
    (llm/register! :load-balanced
      {:router (fn [_]
                 (let [idx (swap! counter inc)
                       providers [{:provider :openai :model "gpt-4o-mini"}
                                  {:provider :anthropic :model "claude-3-haiku-20240307"}]]
                   (nth providers (mod idx (count providers)))))
       :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
                 :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})))

(defn load-balancing-example []
  "Example of load balancing across providers"
  (load-balancing-setup)
  
  ;; Requests alternate between providers
  (when (and (System/getenv "OPENAI_API_KEY")
             (System/getenv "ANTHROPIC_API_KEY"))
    (dotimes [i 4]
      (let [response (llm/chat :load-balanced "Hello")]
        (println (str "Request " (inc i) ": " (llm/extract-content response)))))))

;; ============================================================================
;; Time-Based Routing
;; ============================================================================

(defn time-based-routing-setup []
  "Route based on time of day (e.g., use cheaper models during peak hours)"
  (llm/register! :time-router
    {:router (fn [_]
               (let [hour (.getHour (java.time.LocalTime/now))]
                 (if (and (>= hour 9) (<= hour 17))
                   ;; Peak hours - use cheaper model
                   {:provider :openai :model "gpt-4o-mini"}
                   ;; Off-peak - can use premium
                   {:provider :anthropic :model "claude-3-opus-20240229"})))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}}))

(defn time-based-routing-example []
  "Example of time-based routing"
  (time-based-routing-setup)
  
  (let [response (llm/chat :time-router "What time is it?")]
    (println "Response:" (llm/extract-content response))))

;; ============================================================================
;; Multi-Factor Routing
;; ============================================================================

(defn multi-factor-routing-setup []
  "Combine multiple factors for routing decisions"
  (llm/register! :multi-factor
    {:router (fn [{:keys [user-tier priority messages]}]
               (let [content (-> messages last :content)
                     is-code? (re-find #"(?i)(code|function|debug)" content)]
                 (cond
                   ;; Premium users with urgent requests get best model
                   (and (= user-tier :premium) (= priority :urgent))
                   {:provider :anthropic :model "claude-3-opus-20240229"}
                   
                   ;; Code questions get specialized model
                   is-code?
                   {:provider :openai :model "gpt-4"}
                   
                   ;; Free users get local model
                   (= user-tier :free)
                   {:provider :ollama :model "llama3"}
                   
                   ;; Default
                   :else
                   {:provider :openai :model "gpt-4o-mini"})))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}
               :ollama {:api-base "http://localhost:11434"}}}))

(defn multi-factor-routing-example []
  "Example of multi-factor routing"
  (multi-factor-routing-setup)
  
  ;; Premium + urgent = best model
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [response (llm/completion :multi-factor
                     {:messages [{:role :user :content "Important question"}]
                      :user-tier :premium
                      :priority :urgent
                      :max-tokens 50})]
      (println "Premium urgent:" (llm/extract-content response))))
  
  ;; Code question = GPT-4
  (when (System/getenv "OPENAI_API_KEY")
    (let [response (llm/completion :multi-factor
                     {:messages [{:role :user :content "Debug this code"}]
                      :user-tier :basic
                      :priority :normal
                      :max-tokens 50})]
      (println "Code question:" (llm/extract-content response)))))

;; ============================================================================
;; Examples Runner
;; ============================================================================

(defn -main []
  "Run all advanced pattern examples"
  (println "\n=== Advanced Patterns Examples ===\n")
  
  (when (System/getenv "OPENAI_API_KEY")
    (println "1. A/B Testing:")
    (ab-testing-example)
    
    (println "\n2. Cost Optimization:")
    (cost-optimization-example)
    
    (println "\n3. Priority Routing:")
    (priority-routing-example)
    
    (println "\n4. Content-Based Routing:")
    (content-routing-example)
    
    (println "\n5. Time-Based Routing:")
    (time-based-routing-example))
  
  (when (and (System/getenv "OPENAI_API_KEY")
             (System/getenv "ANTHROPIC_API_KEY"))
    (println "\n6. Load Balancing:")
    (load-balancing-example)
    
    (println "\n7. Multi-Factor Routing:")
    (multi-factor-routing-example))
  
  (println "\n✅ Examples complete!"))

(comment
  ;; Run individual examples in REPL
  
  (ab-testing-example)
  (cost-optimization-example)
  (fallback-chain-example)
  (priority-routing-example)
  (content-routing-example)
  (load-balancing-example)
  (time-based-routing-example)
  (multi-factor-routing-example)
  
  ;; Run all
  (-main))
