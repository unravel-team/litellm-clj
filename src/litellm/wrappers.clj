(ns litellm.wrappers
  "Utility wrappers for provider calls with fallback, retry, timeout, and cost-tracking"
  (:require [clojure.tools.logging :as log]
            [litellm.config :as config]
            [litellm.providers.core :as providers]))

;; ============================================================================
;; Fallback Support
;; ============================================================================

(defn with-fallback
  "Try multiple configurations in order until one succeeds.
  
  Args:
    config-names - Vector of config keywords to try in order
    request-map - The request to send
    completion-fn - Function to call with resolved config and request
  
  Example:
    (with-fallback [:fast :cheap-fallback]
                   {:messages [...]}
                   completion/chat)"
  [config-names request-map completion-fn]
  (when (empty? config-names)
    (throw (ex-info "No fallback configs provided" {:config-names config-names})))
  
  (loop [remaining config-names
         errors []]
    (if (empty? remaining)
      ;; All configs failed
      (throw (ex-info "All fallback configs failed"
                      {:config-names config-names
                       :errors errors}))
      
      ;; Try next config
      (let [config-name (first remaining)
            result (try
                     (log/info "Attempting with config" {:config config-name})
                     {:success true :result (completion-fn config-name request-map)}
                     (catch Exception e
                       (log/warn "Config failed, trying fallback"
                                 {:config config-name
                                  :error (.getMessage e)
                                  :remaining (rest remaining)})
                       {:success false :error e}))]
        (if (:success result)
          (:result result)
          (recur (rest remaining)
                 (conj errors {:config config-name
                              :error (.getMessage (:error result))})))))))

;; ============================================================================
;; Retry Support
;; ============================================================================

(defn- exponential-backoff
  "Calculate exponential backoff delay in milliseconds"
  [attempt base-ms max-ms]
  (min max-ms (* base-ms (Math/pow 2 (dec attempt)))))

(defn with-retry
  "Retry a completion call with exponential backoff.
  
  Args:
    config-name - Config keyword or map
    request-map - The request to send
    completion-fn - Function to call
    opts - Options map with:
           :max-attempts (default 3)
           :backoff-ms (default 1000)
           :max-backoff-ms (default 30000)
           :retry-on - Optional predicate to determine if error should be retried
  
  Example:
    (with-retry :fast
                {:messages [...]}
                completion/chat
                {:max-attempts 3 :backoff-ms 1000})"
  [config-name request-map completion-fn opts]
  (let [{:keys [max-attempts backoff-ms max-backoff-ms retry-on]
         :or {max-attempts 3
              backoff-ms 1000
              max-backoff-ms 30000
              retry-on (constantly true)}} opts]
    
    (loop [attempt 1]
      (let [result (try
                     (log/debug "Attempt" {:attempt attempt :max-attempts max-attempts})
                     {:success true :result (completion-fn config-name request-map)}
                     (catch Exception e
                       {:success false :error e}))]
        (if (:success result)
          (:result result)
          (if (and (< attempt max-attempts)
                   (retry-on (:error result)))
            (let [delay-ms (exponential-backoff attempt backoff-ms max-backoff-ms)]
              (log/warn "Request failed, retrying"
                        {:attempt attempt
                         :max-attempts max-attempts
                         :delay-ms delay-ms
                         :error (.getMessage (:error result))})
              (Thread/sleep delay-ms)
              (recur (inc attempt)))
            
            ;; Max attempts reached or error not retryable
            (do
              (log/error "Request failed after retries"
                         {:attempts attempt
                          :error (.getMessage (:error result))})
              (throw (:error result)))))))))

;; ============================================================================
;; Timeout Support
;; ============================================================================

(defn with-timeout
  "Execute a completion call with a timeout.
  
  Args:
    config-name - Config keyword or map
    request-map - The request to send
    completion-fn - Function to call
    opts - Options map with:
           :timeout-ms (required) - Timeout in milliseconds
  
  Example:
    (with-timeout :fast
                  {:messages [...]}
                  completion/chat
                  {:timeout-ms 30000})"
  [config-name request-map completion-fn opts]
  (let [{:keys [timeout-ms]} opts]
    (when-not timeout-ms
      (throw (ex-info "Timeout not specified" {:opts opts})))
    
    (let [result-promise (promise)
          result-future (future
                          (try
                            (deliver result-promise
                                    {:success true
                                     :result (completion-fn config-name request-map)})
                            (catch Exception e
                              (deliver result-promise
                                      {:success false
                                       :error e}))))
          result (deref result-promise timeout-ms ::timeout)]
      
      ;; Wait for result with timeout
      (cond
        (= result ::timeout)
        (do
          (future-cancel result-future)
          (throw (ex-info "Request timed out"
                          {:timeout-ms timeout-ms
                           :config-name config-name})))
        
        (:success result)
        (:result result)
        
        :else
        (throw (:error result))))))

;; ============================================================================
;; Cost Tracking Support
;; ============================================================================

(defn with-cost-tracking
  "Track the cost of a completion call.
  
  Args:
    config-name - Config keyword or map
    request-map - The request to send
    completion-fn - Function to call
    callback - Function to call with cost information: (fn [cost usage response] ...)
  
  The callback receives:
    - cost: Estimated cost in USD
    - usage: Token usage map {:prompt-tokens N :completion-tokens N :total-tokens N}
    - response: The full response
  
  Example:
    (with-cost-tracking :fast
                        {:messages [...]}
                        completion/chat
                        (fn [cost usage resp]
                          (log/info \"Request cost:\" cost \"USD\")))"
  [config-name request-map completion-fn callback]
  (let [response (completion-fn config-name request-map)
        usage (:usage response)
        
        ;; Resolve config to get provider/model for cost calculation
        resolved-config (if (keyword? config-name)
                         (config/resolve-config config-name request-map)
                         config-name)
        
        provider (:provider resolved-config)
        model (:model resolved-config)
        
        ;; Calculate cost
        cost (providers/calculate-cost
               provider
               model
               (or (:prompt-tokens usage) 0)
               (or (:completion-tokens usage) 0))]
    
    ;; Call callback with cost information
    (try
      (callback cost usage response)
      (catch Exception e
        (log/error "Cost tracking callback failed" {:error (.getMessage e)})))
    
    ;; Return original response
    response))

;; ============================================================================
;; Wrapper Composition
;; ============================================================================

(defn compose-wrappers
  "Compose multiple wrappers together.
  
  Wrappers are applied right-to-left (like function composition).
  
  Example:
    (compose-wrappers
      [(partial with-retry _ _ _ {:max-attempts 3})
       (partial with-timeout _ _ _ {:timeout-ms 30000})
       (partial with-cost-tracking _ _ _ log-cost)]
      :fast
      {:messages [...]}
      completion/chat)"
  [wrappers config-name request-map completion-fn]
  (reduce (fn [f wrapper]
            (fn [cn rm]
              (wrapper cn rm f)))
          completion-fn
          (reverse wrappers)))
