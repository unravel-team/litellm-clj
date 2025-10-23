(ns litellm.config
  "Configuration registry for provider/model switching"
  (:require [clojure.tools.logging :as log]))

;; ============================================================================
;; Configuration Registry
;; ============================================================================

;; "Global registry for provider configurations"
(defonce config-registry
  (atom {}))

(defn register!
  "Register a provider configuration with a keyword name.
  
  Configuration can be:
  - Simple: {:provider :openai :model \"gpt-4\" :config {...}}
  - With router: {:router (fn [request] {...}) :config {...}}
  - Multi-provider: {:router (fn [request] {...}) :configs {:openai {...} :anthropic {...}}}
  
  Examples:
    (register! :fast {:provider :openai :model \"gpt-4o-mini\" :config {:api-key \"...\"}})
    
    (register! :smart {:router (fn [req] 
                                 (if (> (count (:messages req)) 10)
                                   {:provider :anthropic :model \"claude-3-opus\"}
                                   {:provider :openai :model \"gpt-4o-mini\"}))
                       :configs {:openai {:api-key \"...\"} 
                                :anthropic {:api-key \"...\"}}})"
  [config-name config-map]
  (when-not (keyword? config-name)
    (throw (ex-info "Config name must be a keyword" {:config-name config-name})))
  
  (swap! config-registry assoc config-name config-map)
  (log/info "Registered configuration" {:config-name config-name})
  config-name)

(defn unregister!
  "Remove a configuration from the registry"
  [config-name]
  (swap! config-registry dissoc config-name)
  (log/info "Unregistered configuration" {:config-name config-name})
  nil)

(defn get-config
  "Get a registered configuration by name"
  [config-name]
  (get @config-registry config-name))

(defn list-configs
  "List all registered configuration names"
  []
  (keys @config-registry))

(defn clear-registry!
  "Clear all registered configurations (useful for testing)"
  []
  (reset! config-registry {})
  nil)

;; ============================================================================
;; Configuration Resolution
;; ============================================================================

(defn resolve-config
  "Resolve a configuration, applying router function if present.
  
  Returns a map with :provider, :model, and :config keys.
  
  If config has a :router function, it will be called with the request
  to determine provider/model dynamically."
  [config-name request]
  (let [config (get-config config-name)]
    (when-not config
      (throw (ex-info "Configuration not found" 
                      {:config-name config-name 
                       :available (list-configs)})))
    
    (if-let [router (:router config)]
      ;; Config has a router function
      (let [routed (router request)
            provider (:provider routed)
            model (:model routed)
            ;; Get provider-specific config from :configs map or use :config
            provider-config (if-let [configs (:configs config)]
                             (get configs provider)
                             (:config config))]
        {:provider provider
         :model model
         :config provider-config})
      
      ;; Simple static config
      {:provider (:provider config)
       :model (:model config)
       :config (:config config)})))

;; ============================================================================
;; Configuration Helpers
;; ============================================================================

(defn validate-config
  "Validate a configuration map"
  [config-map]
  (cond
    ;; Must have either provider or router
    (and (not (:provider config-map)) 
         (not (:router config-map)))
    (throw (ex-info "Config must have :provider or :router" 
                    {:config config-map}))
    
    ;; If has provider, must have model
    (and (:provider config-map) 
         (not (:model config-map)))
    (throw (ex-info "Config with :provider must have :model" 
                    {:config config-map}))
    
    ;; If has router, router must be a function
    (and (:router config-map) 
         (not (fn? (:router config-map))))
    (throw (ex-info ":router must be a function" 
                    {:config config-map}))
    
    :else true))

(defn register-with-validation!
  "Register a configuration with validation"
  [config-name config-map]
  (validate-config config-map)
  (register! config-name config-map))
