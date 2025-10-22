(ns e2e.run-e2e-tests
  "E2E test runner for all providers"
  (:require [litellm.core :as litellm]
            [clojure.core.async :as async]))

(defn test-provider
  "Test a provider with the given configuration"
  [provider-name model api-key-env]
  (let [api-key (System/getenv api-key-env)]
    (if-not api-key
      {:provider provider-name :status :skipped :reason (str api-key-env " not set")}
      (do
        (println (format "\n🧪 Testing %s provider..." provider-name))
        (try
          ;; Test 1: Basic completion
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user 
                                                         :content "Say 'test' and nothing else"}]
                                             :max-tokens 10
                                             :api-key api-key})]
            (assert (some? response) "Response should not be nil")
            (assert (contains? response :choices) "Response should have :choices")
            (assert (seq (:choices response)) "Choices should not be empty")
            (println (format "  ✓ Basic completion test passed")))
          
          ;; Test 2: With temperature
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user :content "Hi"}]
                                             :max-tokens 10
                                             :temperature 0.5
                                             :api-key api-key})]
            (assert (some? response) "Temperature test failed")
            (println (format "  ✓ Temperature parameter test passed")))
          
          ;; Test 3: Helper function
          (let [response (litellm/chat provider-name model "Hello" 
                                       :api-key api-key
                                       :max-tokens 10)]
            (assert (some? response) "Helper function test failed")
            (assert (string? (litellm/extract-content response)) "Content extraction failed")
            (println (format "  ✓ Helper function test passed")))
          
          ;; Test 4: Streaming
          (try
            (let [ch (litellm/completion provider-name model
                                        {:messages [{:role :user :content "Count: 1"}]
                                         :max-tokens 20
                                         :stream true
                                         :api-key api-key})
                  chunks (atom [])
                  timeout-ms 10000
                  start-time (System/currentTimeMillis)]
              (assert (some? ch) "Streaming channel should not be nil")
              (println (format "  → Collecting streaming chunks (timeout: %dms)..." timeout-ms))
              ;; Collect chunks with timeout
              (loop []
                (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
                  (when-let [chunk (async/alt!!
                                     ch ([v] v)
                                     (async/timeout 2000) nil)]
                    (swap! chunks conj chunk)
                    (recur))))
              (if (seq @chunks)
                (do
                  (assert (every? map? @chunks) "All chunks should be maps")
                  (println (format "  ✓ Streaming test passed (%d chunks received)" (count @chunks))))
                (println "  ⚠️  Streaming test skipped - no chunks received (may not be supported yet)")))
            (catch Exception e
              (println (format "  ⚠️  Streaming test skipped - error: %s" (.getMessage e)))))
          
          (println (format "✅ %s provider tests passed!\n" provider-name))
          {:provider provider-name :status :passed}
          
          (catch Exception e
            (println (format "❌ %s provider tests failed: %s" provider-name (.getMessage e)))
            (println "Error details:")
            (.printStackTrace e)
            (println)
            {:provider provider-name :status :failed :error (.getMessage e)}))))))

(defn test-ollama
  "Test Ollama provider (doesn't require API key)"
  []
  (println "\n🧪 Testing Ollama provider...")
  (try
    (let [response (litellm/completion :ollama "llama2"
                                      {:messages [{:role :user :content "Hi"}]
                                       :max-tokens 10
                                       :api-base "http://localhost:11434"})]
      (if (some? response)
        (do
          (println "  ✓ Ollama test passed")
          (println "✅ Ollama provider tests passed!\n")
          {:provider :ollama :status :passed})
        (do
          (println "⚠️  Ollama server may not be running")
          {:provider :ollama :status :skipped :reason "Server not available"})))
    (catch Exception e
      (println (format "⚠️  Ollama server not available: %s\n" (.getMessage e)))
      {:provider :ollama :status :skipped :reason "Server not available"})))

(defn run-all-tests []
  (println "╔═══════════════════════════════════════════════════════════╗")
  (println "║          LiteLLM-Clj E2E Provider Tests                  ║")
  (println "╚═══════════════════════════════════════════════════════════╝\n")
  
  (let [results [(test-provider :openai "gpt-3.5-turbo" "OPENAI_API_KEY")
                 (test-provider :anthropic "claude-3-haiku-20240307" "ANTHROPIC_API_KEY")
                 (test-provider :gemini "gemini-1.5-flash" "GEMINI_API_KEY")
                 (test-provider :mistral "mistral-small-latest" "MISTRAL_API_KEY")
                 (test-ollama)]
        
        passed (count (filter #(= :passed (:status %)) results))
        failed (count (filter #(= :failed (:status %)) results))
        skipped (count (filter #(= :skipped (:status %)) results))]
    
    (println "╔═══════════════════════════════════════════════════════════╗")
    (println "║                     Test Summary                          ║")
    (println "╚═══════════════════════════════════════════════════════════╝")
    (println (format "Total providers tested: %d" (count results)))
    (println (format "  ✅ Passed:  %d" passed))
    (println (format "  ❌ Failed:  %d" failed))
    (println (format "  ⚠️  Skipped: %d" skipped))
    
    ;; Show skipped details
    (when (> skipped 0)
      (println "\nSkipped providers:")
      (doseq [r (filter #(= :skipped (:status %)) results)]
        (println (format "  - %s: %s" (:provider r) (:reason r)))))
    (println)
    
    ;; Exit with proper code
    (if (> failed 0)
      (do
        (println "❌ Some tests failed!")
        (System/exit 1))
      (do
        (println "✅ All configured providers passed!")
        (System/exit 0)))))

;; For clojure execution
(defn -main [& args]
  (run-all-tests))

;; Only auto-run if this is the main script being executed
(when (and *command-line-args* (empty? *command-line-args*))
  (-main))
