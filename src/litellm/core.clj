(ns litellm.core
  "Main API for LiteLLM Clojure library"
  (:require [clojure.tools.logging :as log]
            [litellm.schemas :as schemas]
            [litellm.threadpool :as threadpool]
            [litellm.providers.core :as providers]
            [litellm.streaming :as streaming]
            [litellm.providers.openai] ; Load to register provider
            [litellm.providers.anthropic] ; Load to register provider
            [litellm.providers.openrouter] ; Load to register provider
            [com.climate.claypoole :as cp]))

;; ============================================================================
;; System State
;; ============================================================================

(defrecord LiteLLMSystem
  [thread-pools providers config])

(def ^:dynamic *system* nil)

;; ============================================================================
;; System Management
;; ============================================================================

(defn create-system
  "Create a new LiteLLM system with the given configuration"
  [config]
  {:pre [(schemas/valid-config? config)]}
  (log/info "Creating LiteLLM system...")
  
  (let [;; Create thread pools
        thread-pools (threadpool/create-thread-pools (:thread-pools config {}))
        
        ;; Create providers
        provider-configs (:providers config {})
        providers (into {}
                       (map (fn [[provider-name provider-config]]
                              [provider-name (providers/create-provider provider-name provider-config)])
                            provider-configs))
        
        ;; Create system record
        system (map->LiteLLMSystem
                 {:thread-pools thread-pools
                  :providers providers
                  :config config})]
    
    (log/info "LiteLLM system created successfully")
    system))

(defn shutdown-system!
  "Shutdown the LiteLLM system gracefully"
  [system]
  (when system
    (log/info "Shutting down LiteLLM system...")
    
    ;; Shutdown thread pools
    (threadpool/shutdown-pools! (:thread-pools system))
    
    (log/info "LiteLLM system shutdown complete")))

;; ============================================================================
;; Provider Management
;; ============================================================================

(defn get-provider
  "Get a provider by name from the system"
  [system provider-name]
  (get (:providers system) provider-name))

(defn list-providers
  "List all available providers in the system"
  [system]
  (keys (:providers system)))

(defn add-provider!
  "Add a new provider to the system"
  [system provider-name provider-config]
  (let [provider (providers/create-provider provider-name provider-config)]
    (swap! (atom (:providers system)) assoc provider-name provider)
    (log/info "Added provider" {:provider provider-name})
    provider))

(defn remove-provider!
  "Remove a provider from the system"
  [system provider-name]
  (swap! (atom (:providers system)) dissoc provider-name)
  (log/info "Removed provider" {:provider provider-name}))

;; ============================================================================
;; Request Routing
;; ============================================================================

(defn select-provider
  "Select the best provider for a request"
  [system request]
  (let [model (:model request)
        provider-name (providers/extract-provider-name model)
        provider (get-provider system provider-name)]
    
    (if provider
      provider
      (throw (ex-info "No provider found for model"
                      {:model model
                       :provider provider-name
                       :available-providers (list-providers system)})))))

(defn make-request
  "Make a request using the appropriate provider"
  [system request]
  {:pre [(schemas/valid-request? request)]}
  
  (let [provider-name (providers/extract-provider-name (:model request))
        thread-pools (:thread-pools system)
        config (get-in system [:config :providers (name provider-name)])]
    
    ;; Validate request against provider capabilities
    (providers/validate-request provider-name request)
    
    ;; Transform request
    (let [transformed-request (providers/transform-request provider-name request config)]
      
      ;; Check if streaming
      (if (:stream request)
        ;; Streaming request - return channel
        (providers/make-streaming-request provider-name transformed-request thread-pools config)
        
        ;; Non-streaming request
        (let [response-future (providers/make-request provider-name transformed-request thread-pools nil config)
              response @response-future]
          ;; Transform response
          (providers/transform-response provider-name response))))))

;; ============================================================================
;; Main API Functions
;; ============================================================================

(defn completion
  "Main completion function - unified interface for all LLM providers"
  [& args]
  (let [ ;; Check if first argument is a map (request-map) or keyword arguments
        request-map (if (and (= 1 (count args)) (map? (first args)))
                      (first args)
                      (let [{:keys [model messages api-key api-base max-tokens temperature top-p
                                    frequency-penalty presence-penalty stream stop tools tool-choice
                                    functions function-call system]
                             :or {stream false temperature 0.7}} (apply hash-map args)]
                        (cond-> {:model model :messages messages}
                          api-key (assoc :api-key api-key)
                          api-base (assoc :api-base api-base)
                          max-tokens (assoc :max-tokens max-tokens)
                          temperature (assoc :temperature temperature)
                          top-p (assoc :top-p top-p)
                          frequency-penalty (assoc :frequency-penalty frequency-penalty)
                          presence-penalty (assoc :presence-penalty presence-penalty)
                          stream (assoc :stream stream)
                          stop (assoc :stop stop)
                          tools (assoc :tools tools)
                          tool-choice (assoc :tool-choice tool-choice)
                          functions (assoc :functions functions)
                          function-call (assoc :function-call function-call))))
        
        ;; Use global system or create temporary one
        system (or *system*
                   (create-system {:providers {"openai" {:provider :openai 
                                                         :api-key (or (:api-key request-map)
                                                                      (System/getenv "OPENAI_API_KEY"))}}
                                   :thread-pools-config {}}))]
    
    (try
      (make-request system request-map)
      (finally
        ;; Only shutdown if we created a temporary system
        (when-not *system*
          (shutdown-system! system))))))

(defn acompletion
  "Async completion function"
  [& args]
  (let [system (or *system*
                   (throw (ex-info "No system available for async completion. Use with-system or set global system."
                                  {})))]
    (cp/future (:api-calls (:thread-pools system))
      (apply completion args))))

;; ============================================================================
;; System Context Macros
;; ============================================================================

(defmacro with-system
  "Execute code with a specific LiteLLM system"
  [system & body]
  `(binding [*system* ~system]
     ~@body))

(defmacro with-config
  "Execute code with a system created from config"
  [config & body]
  `(let [system# (create-system ~config)]
     (try
       (with-system system#
         ~@body)
       (finally
         (shutdown-system! system#)))))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn chat
  "Simple chat completion function"
  [model message & {:keys [system-prompt] :as opts}]
  (let [messages (cond-> [{:role :user :content message}]
                   system-prompt (conj {:role :system :content system-prompt}))]
    (completion (merge {:model model :messages messages} opts))))

(defn embed
  "Text embedding function (placeholder for future implementation)"
  [model text & opts]
  (throw (ex-info "Embedding not yet implemented" {:model model :text text})))

(defn stream-completion
  "Streaming completion function"
  [callback & args]
  (let [request (apply hash-map args)
        request-with-stream (assoc request :stream true)]
    ;; This would need special handling for streaming responses
    (throw (ex-info "Streaming not yet fully implemented" {:request request-with-stream}))))

;; ============================================================================
;; System Information
;; ============================================================================

(defn system-info
  "Get information about the current system"
  [system]
  (when system
    {:providers (into {}
                     (map (fn [[name provider]]
                            [name (providers/provider-status provider)])
                          (:providers system)))
     :thread-pools (threadpool/pool-summary (:thread-pools system))
     :config (:config system)}))

(defn health-check
  "Perform health check on all providers"
  [system]
  (let [providers (:providers system)
        thread-pools (:thread-pools system)]
    (into {}
          (map (fn [[name provider]]
                 [name @(providers/health-check provider thread-pools)])
               providers))))

;; ============================================================================
;; Global System Management
;; ============================================================================

(defn set-global-system!
  "Set the global LiteLLM system"
  [system]
  (alter-var-root #'*system* (constantly system))
  (log/info "Global LiteLLM system set"))

(defn get-global-system
  "Get the global LiteLLM system"
  []
  *system*)

(defn init!
  "Initialize LiteLLM with configuration"
  [config]
  (let [system (create-system config)]
    (set-global-system! system)
    system))

(defn shutdown!
  "Shutdown the global LiteLLM system"
  []
  (when *system*
    (shutdown-system! *system*)
    (alter-var-root #'*system* (constantly nil))))

;; ============================================================================
;; Development and Testing Utilities
;; ============================================================================

(defn test-providers
  "Test all providers in the system"
  [system]
  (let [providers (:providers system)
        thread-pools (:thread-pools system)]
    (into {}
          (map (fn [[name provider]]
                 [name (providers/test-provider provider thread-pools nil)])
               providers))))

(defn benchmark-provider
  "Benchmark a specific provider"
  [system provider-name num-requests]
  (let [provider (get-provider system provider-name)
        start-time (System/currentTimeMillis)]
    
    (dotimes [i num-requests]
      (let [request {:model "test"
                    :messages [{:role :user :content (str "Test request " i)}]
                    :max-tokens 1}]
        (make-request system request)))
    
    (let [end-time (System/currentTimeMillis)
          duration (- end-time start-time)]
      {:provider provider-name
       :requests num-requests
       :duration-ms duration
       :requests-per-second (/ (* num-requests 1000.0) duration)})))

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

;; ============================================================================
;; Configuration Helpers
;; ============================================================================

(defn default-config
  "Get default LiteLLM configuration"
  []
  {:providers {}
   :thread-pools {:api-calls {:pool-size 50}
                 :cache-ops {:pool-size 10}
                 :retries {:pool-size 20}
                 :health-checks {:pool-size 5}
                 :monitoring {:pool-size 2}}})

(defn merge-config
  "Merge user config with defaults"
  [user-config]
  (merge (default-config) user-config))
