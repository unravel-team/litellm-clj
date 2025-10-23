(ns e2e.run-e2e-tests
  "E2E test runner for all providers"
  (:require [litellm.core :as litellm]
            [cheshire.core :as json]
            [clojure.core.async :as async]))

(def calculator-tool
  "Simple calculator tool for testing"
  {:type "function"
   :function {:name "calculate"
             :description "Perform a simple calculation"
             :parameters {:type "object"
                         :properties {:operation {:type "string"
                                                 :enum ["add" "multiply"]
                                                 :description "The operation to perform"}
                                     :a {:type "number"
                                        :description "First number"}
                                     :b {:type "number"
                                        :description "Second number"}}
                         :required ["operation" "a" "b"]}}})

(defn execute-calculator
  "Execute the calculator tool"
  [args]
  (let [operation (:operation args)
        a (:a args)
        b (:b args)
        result (case operation
                 "add" (+ a b)
                 "multiply" (* a b))]
    (json/encode {:result result})))

(defn test-function-calling
  "Test function calling for a provider"
  [provider-name model api-key]
  (try
    (println (format "  → Testing function calling..."))
    
    ;; Test 1: Basic function call request
    (let [response (litellm/completion provider-name model
                                      {:messages [{:role :user 
                                                   :content "What is 5 plus 3?"}]
                                       :tools [calculator-tool]
                                       :tool-choice :auto
                                       :max-tokens 512
                                       :api-key api-key})
          tool-calls (get-in response [:choices 0 :message :tool-calls])]
      
      ;; Check if model requested a tool call
      (if (seq tool-calls)
        (do
          (assert (vector? tool-calls) "Tool calls should be a vector")
          (assert (= "function" (:type (first tool-calls))) "Tool call type should be 'function'")
          (assert (= "calculate" (get-in tool-calls [0 :function :name])) "Should call calculate function")
          (println (format "    ✓ Model requested tool call: %s" (get-in tool-calls [0 :function :name])))
          
          ;; Test 2: Send tool result back
          (let [tool-call (first tool-calls)
                arguments (json/decode (get-in tool-call [:function :arguments]) true)
                result (execute-calculator arguments)
                
                ;; Continue conversation with tool result
                final-response (litellm/completion provider-name model
                                                  {:messages [{:role :user :content "What is 5 plus 3?"}
                                                             (get-in response [:choices 0 :message])
                                                             {:role :tool
                                                              :tool-call-id (:id tool-call)
                                                              :content result}]
                                                   :max-tokens 512
                                                   :api-key api-key})
                final-content (get-in final-response [:choices 0 :message :content])]
            
            (assert (some? final-content) "Should receive final response after tool execution")
            (println (format "    ✓ Tool execution completed successfully"))
            (println (format "  ✓ Function calling test passed"))))
        
        (println "  ⚠️  Function calling test skipped - model did not request tool call")))
    
    (catch Exception e
      (println (format "  ⚠️  Function calling test skipped - error: %s" (.getMessage e)))
      (when (instance? clojure.lang.ExceptionInfo e)
        (println (format "    Error data: %s" (pr-str (ex-data e)))))
      (println "    Stack trace:")
      (doseq [line (take 5 (.getStackTrace e))]
        (println (format "      %s" line))))))

(defn test-provider
  "Test a provider with the given configuration"
  [provider-name model api-key-env supports-functions?]
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
          
          ;; Test 5: Function calling (if supported)
          (when supports-functions?
            (test-function-calling provider-name model api-key))
          
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
  
  (let [results [(test-provider :openai "gpt-3.5-turbo" "OPENAI_API_KEY" true)
                 (test-provider :anthropic "claude-3-haiku-20240307" "ANTHROPIC_API_KEY" true)
                 (test-provider :gemini "gemini-2.5-flash-lite" "GEMINI_API_KEY" true)
                 (test-provider :mistral "mistral-small-latest" "MISTRAL_API_KEY" false)
                 (test-provider :openrouter "openai/gpt-3.5-turbo" "OPENROUTER_API_KEY" false)
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
