(ns examples.threadpool
  "EXAMPLE: Thread pool management using Claypoole for controlled concurrency
  
  This is a reference implementation demonstrating how to build custom threadpool
  management for high-concurrency scenarios. For simple use cases, the core and
  router APIs handle concurrency automatically.
  
  See examples.system for how to use these utilities in a complete system."
  (:require [com.climate.claypoole :as cp]
            [clojure.tools.logging :as log]))

;; ============================================================================
;; Thread Pool Record
;; ============================================================================

(defrecord ThreadPools 
  [api-calls cache-ops retries health-checks monitoring])

;; ============================================================================
;; Pool Management Functions
;; ============================================================================

(defn pool-summary
  "Get summary statistics for all thread pools"
  [pools]
  (into {}
        (map (fn [[name pool]]
               [name (when pool
                       nil)])
             pools)))

;; ============================================================================
;; Thread Pool Creation
;; ============================================================================

(defn create-thread-pools
  "Create all required thread pools with monitoring capabilities"
  [config]
  (let [api-config (:api-calls config {:pool-size 50 :queue-size 1000})
        cache-config (:cache-ops config {:pool-size 10 :queue-size 500})
        retry-config (:retries config {:pool-size 20 :queue-size 200})
        health-config (:health-checks config {:pool-size 5 :queue-size 50})
        monitor-config (:monitoring config {:pool-size 2 :queue-size 10})
        
        pools (map->ThreadPools
                {:api-calls (cp/threadpool (:pool-size api-config)
                                          :name "litellm-api"
                                          :daemon true)
                 :cache-ops (cp/threadpool (:pool-size cache-config)
                                          :name "litellm-cache"
                                          :daemon true)
                 :retries (cp/threadpool (:pool-size retry-config)
                                        :name "litellm-retry"
                                        :daemon true)
                 :health-checks (cp/threadpool (:pool-size health-config)
                                              :name "litellm-health"
                                              :daemon true)
                 :monitoring (cp/threadpool (:pool-size monitor-config)
                                           :name "litellm-monitor"
                                           :daemon true)})]
    
    (log/info "Created thread pools" (pool-summary pools))
    pools))

(defn pool-health
  "Check health of a specific pool"
  [pool]
  (when pool
    (let [stats {}#_(cp/pool-stats pool)]
      {:healthy? (not (.isShutdown pool))
       :active-threads (:active stats)
       :pool-size (:pool-size stats)
       :queue-size (:queue-size stats)
       :completed-tasks (:completed stats)})))

(defn all-pools-health
  "Get health status of all pools"
  [pools]
  (into {}
        (map (fn [[name pool]]
               [name (pool-health pool)])
             pools)))

(defn healthy-pools?
  "Check if all thread pools are healthy"
  [pools]
  (every? (fn [[_ pool]]
            (and pool (not (.isShutdown pool))))
          pools))

;; ============================================================================
;; Pool Utilization Monitoring
;; ============================================================================

(defn pool-utilization
  "Calculate utilization percentage for a pool"
  [pool]
  (when pool
    (let [stats {}#_(cp/pool-stats pool)
          active (:active stats)
          pool-size (:pool-size stats)]
      (if (> pool-size 0)
        (/ active pool-size)
        0.0))))

(defn high-utilization-pools
  "Find pools with utilization above threshold"
  [pools threshold]
  (filter (fn [[name pool]]
            (let [util (pool-utilization pool)]
              (and util (> util threshold))))
          pools))

(defn pool-pressure
  "Calculate pressure on a pool (queue size / active threads)"
  [pool]
  (when pool
    (let [stats {}#_(cp/pool-stats pool)
          queue-size (:queue-size stats)
          active (:active stats)]
      (if (> active 0)
        (/ queue-size active)
        (if (> queue-size 0) Double/POSITIVE_INFINITY 0.0)))))

;; ============================================================================
;; Async Execution Helpers
;; ============================================================================

(defn submit-task
  "Submit a task to a specific thread pool"
  [pool task]
  {:pre [pool task]}
  (cp/future pool (task)))

(defn submit-with-timeout
  "Submit a task with timeout"
  [pool task timeout-ms]
  {:pre [pool task (pos? timeout-ms)]}
  (let [future-result (cp/future pool (task))]
    (try
      (deref future-result timeout-ms ::timeout)
      (catch Exception e
        (future-cancel future-result)
        (throw e)))))

#_(defn submit-with-retry
  "Submit a task with retry logic"
  [pool task max-retries backoff-ms]
  {:pre [pool task (>= max-retries 0) (pos? backoff-ms)]}
  (loop [attempt 1]
    (try
      @(cp/future pool (task))
      (catch Exception e
        (if (< attempt max-retries)
          (do
            (log/debug "Task failed, retrying" {:attempt attempt :error (.getMessage e)})
            (Thread/sleep (* backoff-ms attempt))
            (recur (inc attempt)))
          (do
            (log/error "Task failed after all retries" {:attempts attempt :error (.getMessage e)})
            (throw e)))))))

;; ============================================================================
;; Pool Monitoring
;; ============================================================================

(defn start-pool-monitoring
  "Start background monitoring of thread pools"
  [pools interval-ms callback-fn]
  {:pre [pools (pos? interval-ms) callback-fn]}
  (when-let [monitor-pool (:monitoring pools)]
    (cp/future monitor-pool
      (log/info "Starting thread pool monitoring" {:interval-ms interval-ms})
      (try
        (while (not (.isShutdown monitor-pool))
          (let [health-data (all-pools-health pools)
                summary (pool-summary pools)]
            (callback-fn {:timestamp (System/currentTimeMillis)
                         :health health-data
                         :summary summary})
            
            ;; Log warnings for unhealthy pools
            (doseq [[name health] health-data]
              (when (and health (not (:healthy? health)))
                (log/warn "Unhealthy thread pool detected" {:pool name :health health})))
            
            ;; Log warnings for high utilization
            (doseq [[name pool] (high-utilization-pools pools 0.8)]
              (log/warn "High thread pool utilization" 
                       {:pool name :utilization (pool-utilization pool)})))
          
          (Thread/sleep interval-ms))
        (catch InterruptedException _
          (log/info "Thread pool monitoring interrupted"))
        (catch Exception e
          (log/error "Thread pool monitoring error" e))))))

;; ============================================================================
;; Graceful Shutdown
;; ============================================================================

(defn shutdown-pool!
  "Gracefully shutdown a single thread pool"
  [pool pool-name timeout-ms]
  (when pool
    (log/debug "Shutting down thread pool" {:pool pool-name})
    (try
      (.shutdown pool)
      (when-not (.awaitTermination pool timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (log/warn "Thread pool did not terminate gracefully, forcing shutdown" {:pool pool-name})
        (.shutdownNow pool))
      (log/debug "Thread pool shutdown complete" {:pool pool-name})
      (catch Exception e
        (log/error "Error shutting down thread pool" {:pool pool-name :error (.getMessage e)})))))

(defn shutdown-pools!
  "Gracefully shutdown all thread pools"
  ([pools] (shutdown-pools! pools 5000))
  ([pools timeout-ms]
   (log/info "Shutting down all thread pools...")
   (let [shutdown-order [:monitoring :health-checks :retries :cache-ops :api-calls]]
     (doseq [pool-name shutdown-order]
       (when-let [pool (get pools pool-name)]
         (shutdown-pool! pool pool-name timeout-ms))))
   (log/info "All thread pools shut down")))

;; ============================================================================
;; Pool Statistics and Metrics
;; ============================================================================

(defn collect-pool-metrics
  "Collect comprehensive metrics for all pools"
  [pools]
  (into {}
        (map (fn [[name pool]]
               (when pool
                 (let [stats {}#_(cp/pool-stats pool)
                       health (pool-health pool)]
                   [name (merge stats health
                               {:utilization (pool-utilization pool)
                                :pressure (pool-pressure pool)})])))
             pools)))

(defn pool-performance-report
  "Generate a performance report for all pools"
  [pools]
  (let [metrics (collect-pool-metrics pools)
        total-active (reduce + 0 (map #(get % :active 0) (vals metrics)))
        total-completed (reduce + 0 (map #(get % :completed 0) (vals metrics)))]
    
    {:timestamp (System/currentTimeMillis)
     :total-active-threads total-active
     :total-completed-tasks total-completed
     :pools metrics
     :overall-health (healthy-pools? pools)}))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn wait-for-completion
  "Wait for all tasks in a pool to complete"
  [pool timeout-ms]
  (when pool
    (let [start-time (System/currentTimeMillis)]
      (loop []
        (let [stats {}#_(cp/pool-stats pool)
              active (:active stats)
              queue-size (:queue-size stats)
              elapsed (- (System/currentTimeMillis) start-time)]
          
          (cond
            (and (= 0 active) (= 0 queue-size))
            true
            
            (> elapsed timeout-ms)
            false
            
            :else
            (do
              (Thread/sleep 100)
              (recur))))))))

(defn get-pool-by-name
  "Get a specific pool by name"
  [pools pool-name]
  (get pools pool-name))

(defn pool-available?
  "Check if a pool is available for new tasks"
  [pool]
  (when pool
    (let [stats {}#_(cp/pool-stats pool)]
      (and (not (.isShutdown pool))
           (< (:queue-size stats) (* 0.9 (:max-queue-size stats 1000)))))))
