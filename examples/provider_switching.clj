(ns examples.provider-switching
  "Examples of provider/model switching patterns"
  (:require [litellm.config :as config]
            [litellm.wrappers :as wrappers]
            [litellm.core :as litellm]
            [clojure.tools.logging :as log]))

;; ============================================================================
;; Configuration Examples
;; ============================================================================

(comment
  ;; 1. Simple static configuration
  (config/register! :fast
    {:provider :openai
     :model "gpt-4o-mini"
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})

  ;; 2. Configuration with router function
  (config/register! :smart-routing
    {:router (fn [request]
               ;; Route based on message count
               (if (> (count (:messages request)) 10)
                 {:provider :anthropic :model "claude-3-opus"}
                 {:provider :openai :model "gpt-4o-mini"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})

  ;; 3. Cost-optimized routing
  (config/register! :cost-optimized
    {:router (fn [{:keys [priority user-tier]}]
               (case [priority user-tier]
                 [:high :premium] {:provider :anthropic :model "claude-3-opus"}
                 [:high :free] {:provider :openai :model "gpt-4o-mini"}
                 [:low :premium] {:provider :openai :model "gpt-4o-mini"}
                 [:low :free] {:provider :ollama :model "llama3"}
                 ;; default
                 {:provider :openai :model "gpt-4o-mini"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}
               :ollama {:api-base "http://localhost:11434"}}})

  ;; 4. Cheap fallback for development
  (config/register! :cheap-fallback
    {:provider :ollama
     :model "llama3"
     :config {:api-base "http://localhost:11434"}})

  ;; 5. List all registered configs
  (config/list-configs))

;; ============================================================================
;; Basic Usage Examples
;; ============================================================================

(comment
  ;; Using keyword config name (assuming completion/chat is updated)
  (litellm/chat :fast
    {:messages [{:role :user :content "Hello!"}]})

  ;; Using config with router
  (litellm/chat :smart-routing
    {:messages [{:role :user :content "Analyze this code"}
                {:role :assistant :content "..."}
                ;; ... 10+ messages will route to Claude
                ]})

  ;; Using config with custom routing parameters
  (litellm/chat :cost-optimized
    {:messages [{:role :user :content "Simple query"}]
     :priority :low
     :user-tier :free}))  ;; Routes to Ollama

;; ============================================================================
;; Wrapper Examples
;; ============================================================================

(comment
  ;; Fallback chain - try fast first, fallback to cheap if fails
  (wrappers/with-fallback [:fast :cheap-fallback]
    {:messages [{:role :user :content "Hello"}]}
    litellm/chat)

  ;; Retry with exponential backoff
  (wrappers/with-retry :fast
    {:messages [{:role :user :content "Hello"}]}
    litellm/chat
    {:max-attempts 3
     :backoff-ms 1000
     :max-backoff-ms 30000})

  ;; Only retry on rate limit errors
  (wrappers/with-retry :fast
    {:messages [{:role :user :content "Hello"}]}
    litellm/chat
    {:max-attempts 3
     :retry-on (fn [e]
                 (= :rate-limit-error (:type (ex-data e))))})

  ;; Timeout protection
  (wrappers/with-timeout :fast
    {:messages [{:role :user :content "Hello"}]}
    litellm/chat
    {:timeout-ms 30000})

  ;; Cost tracking
  (wrappers/with-cost-tracking :fast
    {:messages [{:role :user :content "Hello"}]}
    litellm/chat
    (fn [cost usage response]
      (log/info "Request cost:" cost "USD")
      (log/info "Token usage:" usage))))

;; ============================================================================
;; Composing Wrappers
;; ============================================================================

(comment
  ;; Manually compose wrappers
  (-> (litellm/chat :fast {:messages [{:role :user :content "Hello"}]})
      (wrappers/with-retry litellm/chat {:max-attempts 3})
      (wrappers/with-timeout litellm/chat {:timeout-ms 30000})
      (wrappers/with-cost-tracking litellm/chat
        (fn [cost usage resp]
          (log/info "Cost:" cost))))

  ;; Or use compose-wrappers helper
  (wrappers/compose-wrappers
    [(partial wrappers/with-retry _ _ _ {:max-attempts 3})
     (partial wrappers/with-timeout _ _ _ {:timeout-ms 30000})
     (partial wrappers/with-cost-tracking _ _ _
       (fn [cost usage resp] (log/info "Cost:" cost)))]
    :fast
    {:messages [{:role :user :content "Hello"}]}
    litellm/chat))

;; ============================================================================
;; Application-Specific Routing Logic
;; ============================================================================

(defn select-llm-config
  "Application-owned routing logic"
  [task-type context]
  (case task-type
    :code-generation (if (= :premium (:user-tier context))
                       :fast
                       :cheap-fallback)
    :deep-analysis :smart-routing
    :simple-chat :cheap-fallback
    ;; default
    :fast))

(defn analyze-code
  "Example function using custom routing"
  [code user-context]
  (litellm/chat (select-llm-config :code-generation user-context)
    {:messages [{:role :user
                :content (str "Analyze this code:\n" code)}]}))

(comment
  ;; Premium user gets fast model
  (analyze-code "def hello(): pass"
    {:user-tier :premium})

  ;; Free user gets cheap model
  (analyze-code "def hello(): pass"
    {:user-tier :free}))

;; ============================================================================
;; Advanced Pattern: Circuit Breaker + Fallback
;; ============================================================================

(def circuit-state (atom {:failures 0 :last-failure nil}))

(defn circuit-breaker-routing
  "Route based on circuit breaker state"
  [request]
  (let [state @circuit-state
        failures (:failures state)
        last-failure (:last-failure state)
        time-since-failure (when last-failure
                            (- (System/currentTimeMillis) last-failure))]
    
    (cond
      ;; Too many failures, use fallback
      (and (> failures 3)
           (< time-since-failure 60000))  ;; within last minute
      {:provider :ollama :model "llama3"}
      
      ;; Otherwise use primary
      :else
      {:provider :openai :model "gpt-4o-mini"})))

(config/register! :resilient
  {:router circuit-breaker-routing
   :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
             :ollama {:api-base "http://localhost:11434"}}})

(defn chat-with-circuit-breaker
  [request]
  (try
    (let [response (litellm/chat :resilient request)]
      ;; Success - reset circuit
      (swap! circuit-state assoc :failures 0 :last-failure nil)
      response)
    (catch Exception e
      ;; Failure - record it
      (swap! circuit-state
        (fn [state]
          {:failures (inc (:failures state))
           :last-failure (System/currentTimeMillis)}))
      (throw e))))

;; ============================================================================
;; Pattern: A/B Testing
;; ============================================================================

(defn ab-test-routing
  "Simple A/B test - 50/50 split"
  [request]
  (if (< (rand) 0.5)
    {:provider :openai :model "gpt-4o-mini"}
    {:provider :anthropic :model "claude-3-haiku"}))

(config/register! :ab-test
  {:router ab-test-routing
   :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
             :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})

;; ============================================================================
;; Pattern: Feature Flag Integration
;; ============================================================================

(def feature-flags (atom {:use-claude-for-coding true}))

(defn feature-flag-routing
  [{:keys [task-type] :as request}]
  (cond
    (and (= task-type :coding)
         (:use-claude-for-coding @feature-flags))
    {:provider :anthropic :model "claude-3-sonnet"}
    
    :else
    {:provider :openai :model "gpt-4o-mini"}))

(config/register! :feature-flag-based
  {:router feature-flag-routing
   :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
             :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})

;; ============================================================================
;; Complete Example: Production-Ready Setup
;; ============================================================================

(defn setup-production-configs!
  "Setup all production configurations"
  []
  ;; Primary fast model
  (config/register! :primary
    {:provider :openai
     :model "gpt-4o-mini"
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; High-quality model for important tasks
  (config/register! :premium
    {:provider :anthropic
     :model "claude-3-opus"
     :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})
  
  ;; Cheap fallback
  (config/register! :fallback
    {:provider :ollama
     :model "llama3"
     :config {:api-base "http://localhost:11434"}})
  
  ;; Smart routing with multiple strategies
  (config/register! :production
    {:router (fn [{:keys [priority user-tier task-type]}]
               (cond
                 ;; VIP users always get best
                 (= user-tier :vip)
                 {:provider :anthropic :model "claude-3-opus"}
                 
                 ;; High priority tasks get good models
                 (= priority :high)
                 {:provider :openai :model "gpt-4o"}
                 
                 ;; Code tasks get specialized model
                 (= task-type :code)
                 {:provider :anthropic :model "claude-3-sonnet"}
                 
                 ;; Default to fast model
                 :else
                 {:provider :openai :model "gpt-4o-mini"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}}))

(defn production-chat
  "Production-ready chat with all protections"
  [config-name request]
  (-> (wrappers/with-fallback [config-name :fallback]
        request
        litellm/chat)
      ;; Add retry logic
      (wrappers/with-retry litellm/chat
        {:max-attempts 3
         :backoff-ms 1000
         :retry-on (fn [e]
                     (#{:rate-limit-error :timeout-error}
                      (:type (ex-data e))))})
      ;; Add timeout
      (wrappers/with-timeout litellm/chat
        {:timeout-ms 45000})
      ;; Track costs
      (wrappers/with-cost-tracking litellm/chat
        (fn [cost usage response]
          (log/info "Request metrics"
            {:cost cost
             :tokens (:total-tokens usage)
             :model (get-in response [:model])})))))

(comment
  ;; Initialize production configs
  (setup-production-configs!)
  
  ;; Use production chat
  (production-chat :production
    {:messages [{:role :user :content "Hello"}]
     :priority :high
     :user-tier :vip
     :task-type :code}))
