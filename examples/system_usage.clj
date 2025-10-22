(ns examples.system-usage
  "Example usage of litellm.system - system-based API with lifecycle management"
  (:require [litellm.system :as system]
            [clojure.core.async :as async :refer [<!!]]))

;; ============================================================================
;; litellm.system - System-based API with thread pools and streaming
;; ============================================================================

;; System provides:
;; - Thread pool management for concurrent requests
;; - Streaming support
;; - System lifecycle (create, shutdown)
;; - Health checks and monitoring

(defn basic-system-example []
  (println "\n=== Basic System Example ===")
  
  ;; Create a system with configuration
  (let [sys (system/create-system
             {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
              :thread-pools {:api-calls {:pool-size 10}
                            :health-checks {:pool-size 2}}})]
    
    (try
      ;; Make a request
      (let [response (system/completion sys
                                       :openai
                                       "gpt-4o-mini"
                                       {:messages [{:role :user :content "What is 2+2?"}]
                                        :max-tokens 50})]
        (println "Response:" (-> response :choices first :message :content)))
      
      ;; Get system info
      (println "System info:" (system/system-info sys))
      
      (finally
        ;; Always shutdown the system
        (system/shutdown-system! sys)))))

(defn streaming-example []
  (println "\n=== Streaming Example ===")
  
  (let [sys (system/create-system
             {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
              :thread-pools {:api-calls {:pool-size 10}}})]
    
    (try
      ;; Make a streaming request
      (let [stream-ch (system/completion sys
                                        {:provider :openai
                                         :model "gpt-4o-mini"
                                         :messages [{:role :user :content "Count from 1 to 5"}]
                                         :max-tokens 50
                                         :stream true})]
        
        (println "Streaming response:")
        (loop []
          (when-let [chunk (<!! stream-ch)]
            (when-let [content (-> chunk :choices first :delta :content)]
              (print content)
              (flush))
            (recur)))
        (println))
      
      (finally
        (system/shutdown-system! sys)))))

(defn concurrent-requests-example []
  (println "\n=== Concurrent Requests Example ===")
  
  (let [sys (system/create-system
             {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
              :thread-pools {:api-calls {:pool-size 10}}})]
    
    (try
      ;; Make multiple concurrent requests
      (let [requests [{:provider :openai
                      :model "gpt-4o-mini"
                      :messages [{:role :user :content "What is 2+2?"}]
                      :max-tokens 20}
                     {:provider :openai
                      :model "gpt-4o-mini"
                      :messages [{:role :user :content "What is 3+3?"}]
                      :max-tokens 20}
                     {:provider :openai
                      :model "gpt-4o-mini"
                      :messages [{:role :user :content "What is 4+4?"}]
                      :max-tokens 20}]
            
            ;; Execute all requests concurrently
            futures (mapv #(future (system/make-request sys %)) requests)
            
            ;; Wait for all responses
            responses (mapv deref futures)]
        
        (doseq [[i response] (map-indexed vector responses)]
          (println (str "Response " (inc i) ":")
                   (-> response :choices first :message :content))))
      
      (finally
        (system/shutdown-system! sys)))))

(defn with-system-macro-example []
  (println "\n=== with-system Macro Example ===")
  
  (let [sys (system/create-system
             {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
              :thread-pools {:api-calls {:pool-size 10}}})]
    
    (try
      ;; Use with-system for scoped system binding
      (system/with-system sys
        (let [response (system/completion sys
                                         :openai
                                         "gpt-4o-mini"
                                         {:messages [{:role :user :content "Hello!"}]
                                          :max-tokens 30})]
          (println "Response:" (-> response :choices first :message :content))))
      
      (finally
        (system/shutdown-system! sys)))))

(defn with-config-macro-example []
  (println "\n=== with-config Macro Example ===")
  
  ;; with-config automatically creates and shuts down system
  (system/with-config
    {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
     :thread-pools {:api-calls {:pool-size 10}}}
    
    (let [sys (system/get-global-system)
          response (system/completion sys
                                     :openai
                                     "gpt-4o-mini"
                                     {:messages [{:role :user :content "What is LiteLLM?"}]
                                      :max-tokens 100})]
      (println "Response:" (-> response :choices first :message :content)))))

(defn health-check-example []
  (println "\n=== Health Check Example ===")
  
  (let [sys (system/create-system
             {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
              :thread-pools {:api-calls {:pool-size 10}
                            :health-checks {:pool-size 2}}})]
    
    (try
      ;; Perform health check
      (let [health (system/health-check sys)]
        (println "Health check results:" health))
      
      (finally
        (system/shutdown-system! sys)))))

(defn multi-provider-system []
  (println "\n=== Multi-Provider System ===")
  
  (let [sys (system/create-system
             {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
                         :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}
              :thread-pools {:api-calls {:pool-size 20}}})]
    
    (try
      ;; Use different providers
      (when (System/getenv "OPENAI_API_KEY")
        (let [response (system/completion sys
                                         :openai
                                         "gpt-4o-mini"
                                         {:messages [{:role :user :content "OpenAI test"}]
                                          :max-tokens 30})]
          (println "OpenAI response:" (-> response :choices first :message :content))))
      
      (when (System/getenv "ANTHROPIC_API_KEY")
        (let [response (system/completion sys
                                         :anthropic
                                         "claude-3-sonnet-20240229"
                                         {:messages [{:role :user :content "Anthropic test"}]
                                          :max-tokens 30})]
          (println "Anthropic response:" (-> response :choices first :message :content))))
      
      (finally
        (system/shutdown-system! sys)))))

(defn -main []
  (println "litellm.system Examples")
  (println "=======================")
  
  (when (System/getenv "OPENAI_API_KEY")
    (basic-system-example)
    (streaming-example)
    (concurrent-requests-example)
    (with-system-macro-example)
    (with-config-macro-example)
    (health-check-example))
  
  (when (and (System/getenv "OPENAI_API_KEY")
            (System/getenv "ANTHROPIC_API_KEY"))
    (multi-provider-system)))

(comment
  ;; Run examples
  (-main)
  
  ;; Individual examples
  (basic-system-example)
  (streaming-example)
  (concurrent-requests-example)
  (with-system-macro-example)
  (with-config-macro-example)
  (health-check-example)
  (multi-provider-system))
