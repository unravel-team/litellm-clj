(ns examples.error-handling-example
  "Comprehensive examples of error handling in litellm-clj"
  (:require [litellm.core :as llm]
            [litellm.errors :as errors]
            [litellm.streaming :as streaming]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [<!!]]))

;; ============================================================================
;; Basic Error Handling
;; ============================================================================

(defn basic-error-handling []
  "Basic example of catching and handling errors"
  (try
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content "Hello"}]
                     :api-key "invalid-key"})
    (catch clojure.lang.ExceptionInfo e
      (cond
        ;; Check for specific error types
        (errors/authentication-error? e)
        (println "❌ Invalid API key!")
        
        (errors/rate-limit-error? e)
        (println "⏸️  Rate limited, retry after:" 
                 (:retry-after (ex-data e)))
        
        (errors/model-not-found-error? e)
        (println "❌ Model not found")
        
        ;; Generic litellm error
        (errors/litellm-error? e)
        (println "⚠️  LiteLLM error:" (errors/error-summary e))
        
        ;; Unknown error
        :else
        (println "❌ Unexpected error:" (.getMessage e))))))

;; ============================================================================
;; Category-Based Handling
;; ============================================================================

(defn category-based-handling []
  "Handle errors based on their category"
  (try
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content "Hello"}]
                     :api-key (System/getenv "OPENAI_API_KEY")})
    (catch clojure.lang.ExceptionInfo e
      (let [category (errors/get-error-category e)]
        (case category
          :client-error
          (do
            (log/error "Client error - fix your request:")
            (println (errors/error-details e)))
          
          :provider-error
          (do
            (log/warn "Provider issue - might retry...")
            (println "Recoverable?" (errors/recoverable? e)))
          
          :response-error
          (log/error "Invalid response:" (errors/error-details e))
          
          :system-error
          (log/error "System error:" (errors/error-details e))
          
          (throw e))))))

;; ============================================================================
;; Simple Retry Logic
;; ============================================================================

(defn completion-with-retry
  "Retry completion on recoverable errors"
  [provider model request max-retries]
  (loop [attempt 0]
    (try
      (llm/completion provider model request)
      (catch clojure.lang.ExceptionInfo e
        (if (and (errors/should-retry? e 
                                       :max-retries max-retries 
                                       :current-retry attempt)
                 (< attempt max-retries))
          (let [delay (errors/retry-delay e attempt)]
            (log/info "Retrying after" delay "ms"
                      {:error-type (:type (ex-data e))
                       :attempt (inc attempt)})
            (Thread/sleep delay)
            (recur (inc attempt)))
          (throw e))))))

(defn retry-example []
  "Example of using retry logic"
  (completion-with-retry
    :openai
    "gpt-4"
    {:messages [{:role :user :content "Hello"}]
     :api-key (System/getenv "OPENAI_API_KEY")}
    3))

;; ============================================================================
;; Advanced Retry with Timeout
;; ============================================================================

(defn completion-with-retry-timeout
  "Retry with overall operation timeout"
  [provider model request {:keys [max-retries timeout-ms] :as opts}]
  (let [start-time (System/currentTimeMillis)]
    (loop [attempt 0]
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        ;; Check overall timeout
        (when (> elapsed timeout-ms)
          (throw (errors/timeout-error 
                   (name provider)
                   "Overall operation timeout"
                   :timeout-ms timeout-ms)))
        
        (try
          (llm/completion provider model request)
          (catch clojure.lang.ExceptionInfo e
            (if (errors/should-retry? e 
                                      :max-retries max-retries 
                                      :current-retry attempt)
              (let [delay (errors/retry-delay e attempt)
                    remaining (- timeout-ms elapsed)]
                (if (> delay remaining)
                  (throw e)  ; Not enough time to retry
                  (do
                    (Thread/sleep delay)
                    (recur (inc attempt)))))
              (throw e))))))))

(defn timeout-retry-example []
  "Example with timeout and retry"
  (completion-with-retry-timeout
    :openai
    "gpt-4"
    {:messages [{:role :user :content "Hello"}]
     :api-key (System/getenv "OPENAI_API_KEY")}
    {:max-retries 3
     :timeout-ms 30000}))

;; ============================================================================
;; Rate Limit Handling
;; ============================================================================

(defn handle-rate-limits []
  "Specific handling for rate limits"
  (try
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content "Hello"}]
                     :api-key (System/getenv "OPENAI_API_KEY")})
    (catch clojure.lang.ExceptionInfo e
      (when (errors/rate-limit-error? e)
        (let [retry-after (:retry-after (ex-data e))]
          (if retry-after
            (do
              (log/info "Rate limited, waiting" retry-after "seconds")
              (Thread/sleep (* 1000 retry-after))
              ;; Retry the request
              (println "Retrying after rate limit..."))
            ;; No retry-after header, use exponential backoff
            (log/warn "Rate limited without retry-after header")))))))

;; ============================================================================
;; Streaming Error Handling
;; ============================================================================

(defn streaming-basic-error-handling []
  "Basic streaming with error handling"
  (try
    ;; This might throw if streaming is not supported
    (let [ch (llm/completion :openai "gpt-4"
                             {:messages [{:role :user :content "Hello"}]
                              :stream true
                              :api-key (System/getenv "OPENAI_API_KEY")})]
      (loop []
        (when-let [chunk (<!! ch)]
          (if (streaming/is-error-chunk? chunk)
            ;; Handle error chunk
            (do
              (println "\n❌ Streaming error:" (:message chunk))
              (println "   Recoverable?" (:recoverable? chunk))
              (when (:recoverable? chunk)
                (println "   You can retry this request")))
            ;; Process normal chunk
            (do
              (print (streaming/extract-content chunk))
              (flush)
              (recur))))))
    (catch clojure.lang.ExceptionInfo e
      ;; Synchronous errors (e.g., unsupported feature)
      (when (errors/unsupported-feature? e)
        (println "Provider doesn't support streaming")))))

(defn streaming-with-callbacks []
  "Streaming using callbacks for error handling"
  (let [ch (llm/completion :openai "gpt-4"
                           {:messages [{:role :user :content "Write a haiku"}]
                            :stream true
                            :api-key (System/getenv "OPENAI_API_KEY")})]
    (streaming/consume-stream-with-callbacks ch
      ;; on-chunk
      (fn [chunk] 
        (print (streaming/extract-content chunk))
        (flush))
      
      ;; on-complete
      (fn [response] 
        (println "\n✅ Streaming complete!")
        (println "Response ID:" (:id response)))
      
      ;; on-error
      (fn [error-chunk]
        (log/error "Stream error:" (:message error-chunk))
        (when (:recoverable? error-chunk)
          (println "This error is recoverable - you can retry"))))))

;; ============================================================================
;; Error Analysis and Logging
;; ============================================================================

(defn detailed-error-logging []
  "Log detailed error information"
  (try
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content "Hello"}]
                     :api-key "invalid"})
    (catch clojure.lang.ExceptionInfo e
      (when (errors/litellm-error? e)
        ;; Get detailed error information
        (let [details (errors/error-details e)]
          (log/error "Completion failed"
                     {:type (:error-type details)
                      :category (:category details)
                      :message (:message details)
                      :provider (:provider details)
                      :http-status (:http-status details)
                      :provider-code (:provider-code details)
                      :recoverable? (:recoverable? details)
                      :request-id (:request-id details)
                      :context (:context details)}))
        
        ;; Also print human-readable summary
        (println "Error summary:" (errors/error-summary e))))))

;; ============================================================================
;; Provider Fallback Pattern
;; ============================================================================

(defn completion-with-fallback
  "Try multiple providers with fallback"
  [model message providers]
  (loop [[provider & rest-providers] providers]
    (if provider
      (try
        (println "Trying provider:" provider)
        (llm/completion provider model
                        {:messages [{:role :user :content message}]
                         :api-key (System/getenv 
                                    (str (clojure.string/upper-case (name provider)) 
                                         "_API_KEY"))})
        (catch clojure.lang.ExceptionInfo e
          (if (errors/client-error? e)
            ;; Client error - don't try other providers
            (throw e)
            ;; Provider error - try next provider
            (do
              (log/warn "Provider" provider "failed:" (errors/error-summary e))
              (if rest-providers
                (recur rest-providers)
                (throw e))))))
      (throw (ex-info "All providers failed" {})))))

(defn fallback-example []
  "Example of provider fallback"
  (completion-with-fallback
    "gpt-4"
    "What is 2+2?"
    [:openai :anthropic :gemini]))

;; ============================================================================
;; Context Preservation
;; ============================================================================

(defn preserve-error-context []
  "Example showing importance of preserving error context"
  (try
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content "Hello"}]
                     :api-key "invalid"})
    (catch clojure.lang.ExceptionInfo e
      ;; BAD: Just logging the message
      ;; (log/error (.getMessage e))
      
      ;; GOOD: Preserving full context
      (log/error "Completion failed" 
                 {:error (errors/error-details e)
                  :request {:provider :openai
                            :model "gpt-4"}})
      
      ;; Also log request ID for debugging with provider
      (when-let [req-id (:request-id (ex-data e))]
        (log/error "Provider request ID:" req-id)))))

;; ============================================================================
;; Testing Error Scenarios
;; ============================================================================

(defn test-error-types []
  "Test various error scenarios"
  (println "\n=== Testing Error Types ===\n")
  
  ;; Test authentication error
  (try
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content "Hi"}]
                     :api-key "invalid"})
    (catch clojure.lang.ExceptionInfo e
      (println "✅ Authentication error detected:"
               (errors/authentication-error? e))))
  
  ;; Test provider not found
  (try
    (llm/completion :nonexistent "model"
                    {:messages [{:role :user :content "Hi"}]})
    (catch clojure.lang.ExceptionInfo e
      (println "✅ Provider not found detected:"
               (errors/error-type? e :litellm/provider-not-found))))
  
  ;; Test unsupported feature
  (try
    (llm/completion :ollama "llama2"
                    {:messages [{:role :user :content "Hi"}]
                     :stream true})  ; Ollama might not support streaming
    (catch clojure.lang.ExceptionInfo e
      (when (errors/unsupported-feature? e)
        (println "✅ Unsupported feature detected")))))

;; ============================================================================
;; Main Examples Runner
;; ============================================================================

(defn -main []
  "Run all error handling examples"
  (println "=== LiteLLM Error Handling Examples ===\n")
  
  (println "1. Basic error handling:")
  (basic-error-handling)
  
  (println "\n2. Detailed error logging:")
  (detailed-error-logging)
  
  (println "\n3. Testing error types:")
  (test-error-types)
  
  (println "\n4. Rate limit handling:")
  (handle-rate-limits)
  
  (println "\n5. Streaming error handling:")
  (streaming-basic-error-handling)
  
  (println "\n✅ Examples complete!"))

(comment
  ;; Run individual examples in REPL
  
  ;; Basic error handling
  (basic-error-handling)
  
  ;; Retry example (with valid API key)
  (retry-example)
  
  ;; Timeout and retry
  (timeout-retry-example)
  
  ;; Streaming with errors
  (streaming-basic-error-handling)
  
  ;; Provider fallback
  (fallback-example)
  
  ;; Test error types
  (test-error-types)
  
  ;; Run all examples
  (-main))
