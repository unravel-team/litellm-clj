#!/usr/bin/env bb
;; Manual test script for streaming functionality
;; Run with: clojure -M test_streaming_manual.clj
;; Or: bb test_streaming_manual.clj

(require '[litellm.core :as llm]
         '[litellm.streaming :as streaming]
         '[clojure.core.async :refer [go-loop <!]])

(println "=== LiteLLM Streaming Manual Test ===\n")

;; ============================================================================
;; Test 1: Basic OpenAI Streaming
;; ============================================================================

(defn test-openai-streaming []
  (println "Test 1: OpenAI Streaming")
  (println "----------------------------------------")
  (try
    (let [api-key (or (System/getenv "OPENAI_API_KEY")
                      (throw (Exception. "OPENAI_API_KEY not set")))
          ch (llm/completion 
               :model "openai/gpt-4o-mini"
               :messages [{:role :user :content "Count from 1 to 5, one number per line"}]
               :stream true
               :api-key api-key
               :max-tokens 50)]
      
      (print "Response: ")
      (flush)
      
      (go-loop [chunk-count 0]
        (if-let [chunk (<! ch)]
          (do
            (if (streaming/is-error-chunk? chunk)
              (println "\n❌ Error:" (:message chunk))
              (when-let [content (streaming/extract-content chunk)]
                (print content)
                (flush)))
            (recur (inc chunk-count)))
          (do
            (println)
            (println "✓ Stream completed. Received" chunk-count "chunks"))))
      
      ;; Give async operations time to complete
      (Thread/sleep 5000)
      (println "Test 1: ✓ PASSED\n"))
    
    (catch Exception e
      (println "Test 1: ❌ FAILED -" (.getMessage e))
      (println))))

;; ============================================================================
;; Test 2: Callback-Based Streaming
;; ============================================================================

(defn test-callback-streaming []
  (println "Test 2: Callback-Based Streaming")
  (println "----------------------------------------")
  (try
    (let [api-key (or (System/getenv "OPENAI_API_KEY")
                      (throw (Exception. "OPENAI_API_KEY not set")))
          ch (llm/completion 
               :model "openai/gpt-4o-mini"
               :messages [{:role :user :content "Say 'Hello World'"}]
               :stream true
               :api-key api-key
               :max-tokens 20)
          chunks-received (atom 0)]
      
      (print "Response: ")
      (flush)
      
      (streaming/consume-stream-with-callbacks ch
        ;; on-chunk
        (fn [chunk]
          (swap! chunks-received inc)
          (when-let [content (streaming/extract-content chunk)]
            (print content)
            (flush)))
        ;; on-complete
        (fn [response]
          (println)
          (println "✓ Callback completed. Total chunks:" @chunks-received)
          (println "Test 2: ✓ PASSED\n"))
        ;; on-error
        (fn [error]
          (println "\n❌ Error:" (:message error))
          (println "Test 2: ❌ FAILED\n")))
      
      ;; Give callbacks time to complete
      (Thread/sleep 5000))
    
    (catch Exception e
      (println "Test 2: ❌ FAILED -" (.getMessage e))
      (println))))

;; ============================================================================
;; Test 3: Blocking Collection
;; ============================================================================

(defn test-blocking-collection []
  (println "Test 3: Blocking Collection")
  (println "----------------------------------------")
  (try
    (let [api-key (or (System/getenv "OPENAI_API_KEY")
                      (throw (Exception. "OPENAI_API_KEY not set")))
          ch (llm/completion 
               :model "openai/gpt-4o-mini"
               :messages [{:role :user :content "Say 'Testing 123'"}]
               :stream true
               :api-key api-key
               :max-tokens 20)]
      
      (println "Collecting stream...")
      (let [result (streaming/collect-stream ch)]
        (if (:error result)
          (do
            (println "❌ Error:" (get-in result [:error :message]))
            (println "Test 3: ❌ FAILED\n"))
          (do
            (println "Content:" (:content result))
            (println "Total chunks:" (count (:chunks result)))
            (println "Test 3: ✓ PASSED\n")))))
    
    (catch Exception e
      (println "Test 3: ❌ FAILED -" (.getMessage e))
      (println))))

;; ============================================================================
;; Test 4: Anthropic Streaming (Optional)
;; ============================================================================

(defn test-anthropic-streaming []
  (println "Test 4: Anthropic Streaming (Optional)")
  (println "----------------------------------------")
  (if-let [api-key (System/getenv "ANTHROPIC_API_KEY")]
    (try
      (let [ch (llm/completion 
                 :model "anthropic/claude-3-haiku"
                 :messages [{:role :user :content "Say 'Anthropic works'"}]
                 :stream true
                 :api-key api-key
                 :max-tokens 20)]
        
        (print "Response: ")
        (flush)
        
        (go-loop []
          (if-let [chunk (<! ch)]
            (do
              (when-let [content (streaming/extract-content chunk)]
                (print content)
                (flush))
              (recur))
            (println)))
        
        (Thread/sleep 5000)
        (println "Test 4: ✓ PASSED\n"))
      
      (catch Exception e
        (println "Test 4: ❌ FAILED -" (.getMessage e))
        (println)))
    (do
      (println "ANTHROPIC_API_KEY not set - skipping")
      (println "Test 4: ⊘ SKIPPED\n"))))

;; ============================================================================
;; Run All Tests
;; ============================================================================

(defn -main []
  (println "Prerequisites:")
  (println "- OPENAI_API_KEY must be set")
  (println "- ANTHROPIC_API_KEY optional for Test 4\n")
  
  (if (System/getenv "OPENAI_API_KEY")
    (do
      (test-openai-streaming)
      (test-callback-streaming)
      (test-blocking-collection)
      (test-anthropic-streaming)
      
      (println "=== All Tests Completed ===")
      (System/exit 0))
    (do
      (println "❌ ERROR: OPENAI_API_KEY environment variable not set")
      (println "Please set it with: export OPENAI_API_KEY=your-key-here")
      (System/exit 1))))

;; Run if executed directly
(when (= *file* (System/getProperty "babashka.file"))
  (-main))

;; For REPL usage
(comment
  (test-openai-streaming)
  (test-callback-streaming)
  (test-blocking-collection)
  (test-anthropic-streaming))
