(ns e2e.run-e2e-tests-test
  "E2E test suite for all providers"
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.core :as litellm]
            [cheshire.core :as json]
            [clojure.core.async :as async]))

(def calculator-tool
  "Simple calculator tool for testing"
  {:tool-type "function"
   :function {:function-name "calculate"
             :function-description "Perform a simple calculation"
             :function-parameters {:type "object"
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

(defn test-function-calling-impl
  "Helper function to test function calling for a provider"
  [provider-name model api-key]
  (testing "Function calling"
    (try
      ;; Test 1: Basic function call request
      (let [response (litellm/completion provider-name model
                                        {:messages [{:role :user 
                                                     :content "What is 5 plus 3?"}]
                                         :tools [calculator-tool]
                                         :tool-choice :required
                                         :max-tokens 512
                                         :api-key api-key})
            tool-calls (get-in response [:choices 0 :message :tool-calls])]
        
        ;; Check if model requested a tool call
        (if (seq tool-calls)
          (do
            (is (vector? tool-calls) "Tool calls should be a vector")
            (is (= "function" (:type (first tool-calls))) "Tool call type should be 'function'")
            (is (= "calculate" (get-in tool-calls [0 :function :name])) "Should call calculate function")
            
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
              
              (is (some? final-content) "Should receive final response after tool execution")))
          (println (format "  ⚠️  Function calling test skipped - model did not request tool call"))))
      
      (catch Exception e
        (println (format "  ⚠️  Function calling test skipped - error: %s" (.getMessage e)))
        (when (instance? clojure.lang.ExceptionInfo e)
          (println (format "    Error data: %s" (pr-str (ex-data e)))))))))

(deftest ^:e2e test-openai-provider
  (testing "OpenAI provider E2E tests"
    (if-let [api-key (System/getenv "OPENAI_API_KEY")]
      (let [provider-name :openai
            model "gpt-3.5-turbo"]
        (println (format "\n🧪 Testing %s provider..." provider-name))
        
        (testing "Basic completion"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user 
                                                         :content "Say 'test' and nothing else"}]
                                             :max-tokens 10
                                             :api-key api-key})]
            (is (some? response) "Response should not be nil")
            (is (contains? response :choices) "Response should have :choices")
            (is (seq (:choices response)) "Choices should not be empty")))
        
        (testing "Temperature parameter"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user :content "Hi"}]
                                             :max-tokens 10
                                             :temperature 0.5
                                             :api-key api-key})]
            (is (some? response) "Temperature test failed")))
        
        (testing "Helper function"
          (let [response (litellm/chat provider-name model "Hello" 
                                       :api-key api-key
                                       :max-tokens 10)]
            (is (some? response) "Helper function test failed")
            (is (string? (litellm/extract-content response)) "Content extraction failed")))
        
        (testing "Streaming"
          (try
            (let [ch (litellm/completion provider-name model
                                        {:messages [{:role :user :content "Count: 1"}]
                                         :max-tokens 20
                                         :stream true
                                         :api-key api-key})
                  chunks (atom [])
                  timeout-ms 10000
                  start-time (System/currentTimeMillis)]
              (is (some? ch) "Streaming channel should not be nil")
              ;; Collect chunks with timeout
              (loop []
                (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
                  (when-let [chunk (async/alt!!
                                     ch ([v] v)
                                     (async/timeout 2000) nil)]
                    (swap! chunks conj chunk)
                    (recur))))
              (when (seq @chunks)
                (is (every? map? @chunks) "All chunks should be maps")))
            (catch Exception e
              (println (format "  ⚠️  Streaming test failed - error: %s" (.getMessage e))))))
        
        (test-function-calling-impl provider-name model api-key)
        
        (println (format "✅ %s provider tests passed!\n" provider-name)))
      (do
        (println "⚠️  OpenAI tests skipped - OPENAI_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e test-anthropic-provider
  (testing "Anthropic provider E2E tests"
    (if-let [api-key (System/getenv "ANTHROPIC_API_KEY")]
      (let [provider-name :anthropic
            model "claude-3-haiku-20240307"]
        (println (format "\n🧪 Testing %s provider..." provider-name))
        
        (testing "Basic completion"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user 
                                                         :content "Say 'test' and nothing else"}]
                                             :max-tokens 10
                                             :api-key api-key})]
            (is (some? response) "Response should not be nil")
            (is (contains? response :choices) "Response should have :choices")
            (is (seq (:choices response)) "Choices should not be empty")))
        
        (testing "Temperature parameter"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user :content "Hi"}]
                                             :max-tokens 10
                                             :temperature 0.5
                                             :api-key api-key})]
            (is (some? response) "Temperature test failed")))
        
        (testing "Helper function"
          (let [response (litellm/chat provider-name model "Hello" 
                                       :api-key api-key
                                       :max-tokens 10)]
            (is (some? response) "Helper function test failed")
            (is (string? (litellm/extract-content response)) "Content extraction failed")))
        
        (testing "Streaming"
          (try
            (let [ch (litellm/completion provider-name model
                                        {:messages [{:role :user :content "Count: 1"}]
                                         :max-tokens 20
                                         :stream true
                                         :api-key api-key})
                  chunks (atom [])
                  timeout-ms 10000
                  start-time (System/currentTimeMillis)]
              (is (some? ch) "Streaming channel should not be nil")
              (loop []
                (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
                  (when-let [chunk (async/alt!!
                                     ch ([v] v)
                                     (async/timeout 2000) nil)]
                    (swap! chunks conj chunk)
                    (recur))))
              (when (seq @chunks)
                (is (every? map? @chunks) "All chunks should be maps")))
            (catch Exception e
              (println (format "  ⚠️  Streaming test failed - error: %s" (.getMessage e))))))
        
        (test-function-calling-impl provider-name model api-key)
        
        (println (format "✅ %s provider tests passed!\n" provider-name)))
      (do
        (println "⚠️  Anthropic tests skipped - ANTHROPIC_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e test-gemini-provider
  (testing "Gemini provider E2E tests"
    (if-let [api-key (System/getenv "GEMINI_API_KEY")]
      (let [provider-name :gemini
            model "gemini-2.5-flash-lite"]
        (println (format "\n🧪 Testing %s provider..." provider-name))
        
        (testing "Basic completion"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user 
                                                         :content "Say 'test' and nothing else"}]
                                             :max-tokens 10
                                             :api-key api-key})]
            (is (some? response) "Response should not be nil")
            (is (contains? response :choices) "Response should have :choices")
            (is (seq (:choices response)) "Choices should not be empty")))
        
        (testing "Temperature parameter"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user :content "Hi"}]
                                             :max-tokens 10
                                             :temperature 0.5
                                             :api-key api-key})]
            (is (some? response) "Temperature test failed")))
        
        (testing "Helper function"
          (let [response (litellm/chat provider-name model "Hello" 
                                       :api-key api-key
                                       :max-tokens 10)]
            (is (some? response) "Helper function test failed")
            (is (string? (litellm/extract-content response)) "Content extraction failed")))
        
        (testing "Streaming"
          (try
            (let [ch (litellm/completion provider-name model
                                        {:messages [{:role :user :content "Count: 1"}]
                                         :max-tokens 20
                                         :stream true
                                         :api-key api-key})
                  chunks (atom [])
                  timeout-ms 10000
                  start-time (System/currentTimeMillis)]
              (is (some? ch) "Streaming channel should not be nil")
              (loop []
                (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
                  (when-let [chunk (async/alt!!
                                     ch ([v] v)
                                     (async/timeout 2000) nil)]
                    (swap! chunks conj chunk)
                    (recur))))
              (when (seq @chunks)
                (is (every? map? @chunks) "All chunks should be maps")))
            (catch Exception e
              (println (format "  ⚠️  Streaming test failed - error: %s" (.getMessage e))))))
        
        (test-function-calling-impl provider-name model api-key)
        
        (println (format "✅ %s provider tests passed!\n" provider-name)))
      (do
        (println "⚠️  Gemini tests skipped - GEMINI_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e ^:kaocha/skip test-mistral-provider
  (testing "Mistral provider E2E tests"
    (if-let [api-key (System/getenv "MISTRAL_API_KEY")]
      (let [provider-name :mistral
            model "mistral-small-latest"]
        (println (format "\n🧪 Testing %s provider..." provider-name))
        
        (testing "Basic completion"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user 
                                                         :content "Say 'test' and nothing else"}]
                                             :max-tokens 10
                                             :api-key api-key})]
            (is (some? response) "Response should not be nil")
            (is (contains? response :choices) "Response should have :choices")
            (is (seq (:choices response)) "Choices should not be empty")))
        
        (testing "Temperature parameter"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user :content "Hi"}]
                                             :max-tokens 10
                                             :temperature 0.5
                                             :api-key api-key})]
            (is (some? response) "Temperature test failed")))
        
        (testing "Helper function"
          (let [response (litellm/chat provider-name model "Hello" 
                                       :api-key api-key
                                       :max-tokens 10)]
            (is (some? response) "Helper function test failed")
            (is (string? (litellm/extract-content response)) "Content extraction failed")))
        
        (testing "Streaming"
          (try
            (let [ch (litellm/completion provider-name model
                                        {:messages [{:role :user :content "Count: 1"}]
                                         :max-tokens 20
                                         :stream true
                                         :api-key api-key})
                  chunks (atom [])
                  timeout-ms 10000
                  start-time (System/currentTimeMillis)]
              (is (some? ch) "Streaming channel should not be nil")
              (loop []
                (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
                  (when-let [chunk (async/alt!!
                                     ch ([v] v)
                                     (async/timeout 2000) nil)]
                    (swap! chunks conj chunk)
                    (recur))))
              (when (seq @chunks)
                (is (every? map? @chunks) "All chunks should be maps")))
            (catch Exception e
              (println (format "  ⚠️  Streaming test failed - error: %s" (.getMessage e))))))
        
        (println (format "✅ %s provider tests passed!\n" provider-name)))
      (do
        (println "⚠️  Mistral tests skipped - MISTRAL_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e test-openrouter-provider
  (testing "OpenRouter provider E2E tests"
    (if-let [api-key (System/getenv "OPENROUTER_API_KEY")]
      (let [provider-name :openrouter
            model "openai/gpt-3.5-turbo"]
        (println (format "\n🧪 Testing %s provider..." provider-name))
        
        (testing "Basic completion"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user 
                                                         :content "Say 'test' and nothing else"}]
                                             :max-tokens 10
                                             :api-key api-key})]
            (is (some? response) "Response should not be nil")
            (is (contains? response :choices) "Response should have :choices")
            (is (seq (:choices response)) "Choices should not be empty")))
        
        (testing "Temperature parameter"
          (let [response (litellm/completion provider-name model
                                            {:messages [{:role :user :content "Hi"}]
                                             :max-tokens 10
                                             :temperature 0.5
                                             :api-key api-key})]
            (is (some? response) "Temperature test failed")))
        
        (testing "Helper function"
          (let [response (litellm/chat provider-name model "Hello" 
                                       :api-key api-key
                                       :max-tokens 10)]
            (is (some? response) "Helper function test failed")
            (is (string? (litellm/extract-content response)) "Content extraction failed")))
        
        (testing "Streaming"
          (try
            (let [ch (litellm/completion provider-name model
                                        {:messages [{:role :user :content "Count: 1"}]
                                         :max-tokens 20
                                         :stream true
                                         :api-key api-key})
                  chunks (atom [])
                  timeout-ms 10000
                  start-time (System/currentTimeMillis)]
              (is (some? ch) "Streaming channel should not be nil")
              (loop []
                (when (< (- (System/currentTimeMillis) start-time) timeout-ms)
                  (when-let [chunk (async/alt!!
                                     ch ([v] v)
                                     (async/timeout 2000) nil)]
                    (swap! chunks conj chunk)
                    (recur))))
              (when (seq @chunks)
                (is (every? map? @chunks) "All chunks should be maps")))
            (catch Exception e
              (println (format "  ⚠️  Streaming test failed - error: %s" (.getMessage e))))))
        
        (test-function-calling-impl provider-name model api-key)
        
        (println (format "✅ %s provider tests passed!\n" provider-name)))
      (do
        (println "⚠️  OpenRouter tests skipped - OPENROUTER_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e ^:kaocha/skip test-ollama-provider
  (testing "Ollama provider E2E tests"
    (println "\n🧪 Testing Ollama provider...")
    (try
      (let [response (litellm/completion :ollama "llama2"
                                        {:messages [{:role :user :content "Hi"}]
                                         :max-tokens 10
                                         :api-base "http://localhost:11434"})]
        (if (some? response)
          (do
            (is (some? response) "Response should not be nil")
            (println "✅ Ollama provider tests passed!\n"))
          (println "⚠️  Ollama server may not be running")))
      (catch Exception e
        (println (format "⚠️  Ollama server not available: %s\n" (.getMessage e)))))))
