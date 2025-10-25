(ns examples.system
  "EXAMPLE: System lifecycle and management for LiteLLM
  
  This namespace demonstrates how to build a full-featured system with:
  - Custom threadpool configuration and management
  - Lifecycle management (create/shutdown)
  - Health monitoring and observability
  
  This is a REFERENCE IMPLEMENTATION - not part of the core API.
  For simple use cases, use litellm.core or litellm.router instead."
  (:require [clojure.tools.logging :as log]
            [litellm.schemas :as schemas]
            [examples.threadpool :as threadpool]
            [litellm.providers.core :as providers]
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
        
        ;; In multimethod-based system, providers are just configs, not objects
        provider-configs (:providers config {})
        
        ;; Create system record
        system (map->LiteLLMSystem
                 {:thread-pools thread-pools
                  :providers provider-configs
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
  ;; In multimethod-based system, just add config
  (swap! (atom (:providers system)) assoc provider-name provider-config)
  (log/info "Added provider" {:provider provider-name})
  provider-config)

(defn remove-provider!
  "Remove a provider from the system"
  [system provider-name]
  (swap! (atom (:providers system)) dissoc provider-name)
  (log/info "Removed provider" {:provider provider-name}))

;; ============================================================================
;; System-based Request Handling
;; ============================================================================

(defn make-request
  "Make a request using the system's thread pools and provider configurations
  
  Request must include :provider and :model keys explicitly."
  [system request]
  {:pre [(schemas/valid-request? request)]}
  
  (let [provider-name (:provider request)
        model (:model request)
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

(defn completion
  "Completion function using system for thread pools and configs
  
  Usage:
    (completion system :openai \"gpt-4\" {:messages [...]})
    (completion system {:provider :openai :model \"gpt-4\" :messages [...]})"
  [system & args]
  (let [request-map (cond
                      ;; Map as first argument
                      (and (= 1 (count args)) (map? (first args)))
                      (first args)
                      
                      ;; Provider and model as first two args
                      (and (>= (count args) 3) 
                           (keyword? (first args)))
                      (let [[provider model request-or-opts & rest-args] args
                            base-request (if (map? request-or-opts)
                                          request-or-opts
                                          {:messages request-or-opts})]
                        (assoc base-request
                               :provider provider
                               :model model))
                      
                      ;; Keyword args style
                      :else
                      (let [{:keys [provider model messages api-key api-base max-tokens temperature top-p
                                    frequency-penalty presence-penalty stream stop tools tool-choice
                                    functions function-call]
                             :or {stream false temperature 0.7}} (apply hash-map args)]
                        (cond-> {:provider provider :model model :messages messages}
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
                          function-call (assoc :function-call function-call))))]
    
    (make-request system request-map)))

(defn acompletion
  "Async completion function using system"
  [system & args]
  (cp/future (:api-calls (:thread-pools system))
    (apply completion system args)))

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
;; System Information
;; ============================================================================

(defn system-info
  "Get information about the current system"
  [system]
  (when system
    {:providers (into {}
                     (map (fn [[name config]]
                            [name {:name name :config config}])
                          (:providers system)))
     :thread-pools (threadpool/pool-summary (:thread-pools system))
     :config (:config system)}))

(defn health-check
  "Perform health check on all providers"
  [system]
  (let [provider-configs (:providers system)
        thread-pools (:thread-pools system)]
    (into {}
          (map (fn [[name config]]
                 [name @(providers/health-check name thread-pools config)])
               provider-configs))))

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
  (let [provider-configs (:providers system)
        thread-pools (:thread-pools system)]
    (into {}
          (map (fn [[name config]]
                 [name (providers/test-provider name thread-pools nil config)])
               provider-configs))))

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
