(ns litellm.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.core :as core]
            [litellm.schemas]
            [litellm.errors :as errors]))

;; ============================================================================
;; Provider Discovery Tests
;; ============================================================================

(deftest test-list-providers
  (testing "List all available providers"
    (let [providers (core/list-providers)]
      (is (coll? providers))
      (is (seq providers))
      (is (contains? providers :openai))
      (is (contains? providers :anthropic))
      (is (contains? providers :gemini)))))

(deftest test-provider-available
  (testing "Check if provider is available"
    (is (true? (core/provider-available? :openai)))
    (is (true? (core/provider-available? :anthropic)))
    (is (false? (core/provider-available? :nonexistent)))))

(deftest test-provider-info
  (testing "Get provider information"
    (let [info (core/provider-info :openai)]
      (is (map? info))
      (is (= :openai (:name info))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-supports-streaming
  (testing "Check streaming support for providers"
    (is (true? (core/supports-streaming? :openai)))
    (is (true? (core/supports-streaming? :anthropic)))
    (is (true? (core/supports-streaming? :gemini)))))


(deftest test-validate-request
  (testing "Validate request structure"
    ;; Valid request should not throw
    (is (nil? (core/validate-request :openai {:model "gpt-4" :messages [{:role :user :content "test"}]})))
    
    ;; Invalid request should throw
    (is (thrown? Exception
                 (core/validate-request :openai {:model "gpt-4"})))))

;; ============================================================================
;; Response Utilities Tests
;; ============================================================================

(deftest test-extract-content
  (testing "Extract content from response"
    (let [response {:choices [{:message {:role :assistant :content "Hello there"}}]}]
      (is (= "Hello there" (core/extract-content response))))
    
    (testing "Returns nil for missing content"
      (is (nil? (core/extract-content {})))
      (is (nil? (core/extract-content {:choices []}))))))

(deftest test-extract-message
  (testing "Extract message from response"
    (let [response {:choices [{:message {:role :assistant :content "Hello"}}]}
          message (core/extract-message response)]
      (is (= :assistant (:role message)))
      (is (= "Hello" (:content message))))
    
    (testing "Returns nil for missing message"
      (is (nil? (core/extract-message {})))
      (is (nil? (core/extract-message {:choices []}))))))

(deftest test-extract-usage
  (testing "Extract usage information from response"
    (let [response {:usage {:prompt-tokens 10 :completion-tokens 20 :total-tokens 30}}
          usage (core/extract-usage response)]
      (is (= 10 (:prompt-tokens usage)))
      (is (= 20 (:completion-tokens usage)))
      (is (= 30 (:total-tokens usage))))
    
    (testing "Returns nil for missing usage"
      (is (nil? (core/extract-usage {}))))))

;; ============================================================================
;; Cost Estimation Tests
;; ============================================================================

(deftest test-estimate-tokens
  (testing "Estimate token count for text"
    (let [text "Hello world"
          tokens (core/estimate-tokens text)]
      (is (number? tokens))
      (is (pos? tokens)))
    
    (testing "Longer text has more tokens"
      (let [short-text "Hi"
            long-text "This is a much longer text that should have more tokens"
            short-tokens (core/estimate-tokens short-text)
            long-tokens (core/estimate-tokens long-text)]
        (is (> long-tokens short-tokens))))))

(deftest test-estimate-request-tokens
  (testing "Estimate tokens for a request"
    (let [request {:messages [{:role :user :content "What is 2+2?"}
                             {:role :assistant :content "The answer is 4"}]}
          tokens (core/estimate-request-tokens request)]
      (is (number? tokens))
      (is (pos? tokens)))
    
    (testing "Request with more messages has more tokens"
      (let [small-request {:messages [{:role :user :content "Hi"}]}
            large-request {:messages [{:role :user :content "Hi"}
                                     {:role :assistant :content "Hello"}
                                     {:role :user :content "How are you?"}
                                     {:role :assistant :content "I'm doing great!"}]}
            small-tokens (core/estimate-request-tokens small-request)
            large-tokens (core/estimate-request-tokens large-request)]
        (is (> large-tokens small-tokens))))))

(deftest test-calculate-cost
  (testing "Calculate cost for known models"
    (let [cost (core/calculate-cost :openai "gpt-4" 1000 500)]
      (is (number? cost))
      (is (pos? cost)))
    
    (testing "More tokens = higher cost"
      (let [small-cost (core/calculate-cost :openai "gpt-4" 100 50)
            large-cost (core/calculate-cost :openai "gpt-4" 10000 5000)]
        (is (> large-cost small-cost))))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest test-completion-provider-not-found
  (testing "Completion throws error for unknown provider"
    (is (thrown-with-msg? Exception #"Provider not found"
                          (core/completion :nonexistent "model" 
                                          {:messages [{:role :user :content "test"}]})))))

(deftest test-completion-invalid-request
  (testing "Completion throws error for invalid request"
    (is (thrown? Exception
                 (core/completion :openai "gpt-4" {})))))

(deftest test-with-error-handling
  (testing "Error handling wrapper catches and re-throws litellm errors"
    (is (thrown? Exception
                 (core/with-error-handling
                   #(throw (errors/invalid-request "Test error"))))))
  
  (testing "Error handling wrapper catches generic exceptions"
    (is (thrown? Exception
                 (core/with-error-handling
                   #(throw (Exception. "Generic error")))))))

;; ============================================================================
;; Provider-Specific Convenience Functions Tests
;; ============================================================================

(deftest test-openai-completion
  (testing "OpenAI completion function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/openai-completion "gpt-4" 
                                        {:messages [{:role :user :content "test"}]})))))

(deftest test-anthropic-completion
  (testing "Anthropic completion function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/anthropic-completion "claude-3-sonnet-20240229"
                                           {:messages [{:role :user :content "test"}]})))))

(deftest test-gemini-completion
  (testing "Gemini completion function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/gemini-completion "gemini-pro"
                                        {:messages [{:role :user :content "test"}]})))))

(deftest test-mistral-completion
  (testing "Mistral completion function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/mistral-completion "mistral-medium"
                                         {:messages [{:role :user :content "test"}]})))))

(deftest test-ollama-completion
  (testing "Ollama completion function exists"
    ;; Ollama might fail with connection error if not running locally
    (is (thrown? Exception
                 (core/ollama-completion "llama3"
                                        {:messages [{:role :user :content "test"}]})))))

(deftest test-openrouter-completion
  (testing "OpenRouter completion function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/openrouter-completion "openai/gpt-4"
                                            {:messages [{:role :user :content "test"}]})))))

;; ============================================================================
;; Chat Convenience Function Tests
;; ============================================================================

(deftest test-chat-function
  (testing "Chat function creates proper request"
    ;; Without system prompt - should throw error without API key
    (is (thrown? Exception
                 (core/chat :openai "gpt-4" "What is 2+2?"
                           :api-key nil)))
    
    ;; With system prompt - should throw error without API key
    (is (thrown? Exception
                 (core/chat :openai "gpt-4" "What is 2+2?"
                           :system-prompt "You are a math tutor"
                           :api-key nil)))))

;; ============================================================================
;; Embedding API Tests
;; ============================================================================

(deftest test-embedding-provider-not-found
  (testing "Embedding throws error for unknown provider"
    (is (thrown-with-msg? Exception #"Provider not found"
                          (core/embedding :nonexistent "model" 
                                         {:input "test"})))))

(deftest test-embedding-invalid-request
  (testing "Embedding throws error for invalid request"
    (is (thrown? Exception
                 (core/embedding :openai "text-embedding-3-small" {})))))

(deftest test-embedding-unsupported-provider
  (testing "Embedding throws error for provider without embedding support"
    (is (thrown-with-msg? Exception #"doesn't support embeddings"
                          (core/embedding :anthropic "claude-3-sonnet-20240229"
                                         {:input "test"})))))

(deftest test-openai-embedding
  (testing "OpenAI embedding function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/openai-embedding "text-embedding-3-small"
                                       {:input "test"})))))

(deftest test-mistral-embedding
  (testing "Mistral embedding function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/mistral-embedding "mistral-embed"
                                        {:input "test"})))))

(deftest test-gemini-embedding
  (testing "Gemini embedding function exists and delegates correctly"
    ;; Without API key, should throw an error
    (is (thrown? Exception
                 (core/gemini-embedding "text-embedding-004"
                                       {:input "test"})))))

;; ============================================================================
;; Integration Tests (require API keys)
;; ============================================================================

(deftest ^:integration test-core-completion-openai
  (testing "OpenAI completion integration test"
    (when (System/getenv "OPENAI_API_KEY")
      (let [request {:messages [{:role :user :content "Say 'test successful' and nothing else"}]
                     :max-tokens 10
                     :api-key (System/getenv "OPENAI_API_KEY")}
            response (core/completion :openai "gpt-3.5-turbo" request)]
        (is (map? response))
        (is (contains? response :choices))
        (is (seq (:choices response)))
        (is (string? (core/extract-content response)))))))

(deftest ^:integration test-core-chat-openai
  (testing "OpenAI chat integration test"
    (when (System/getenv "OPENAI_API_KEY")
      (let [response (core/chat :openai "gpt-3.5-turbo" 
                               "Say 'test successful'"
                               :api-key (System/getenv "OPENAI_API_KEY")
                               :max-tokens 10)]
        (is (map? response))
        (is (string? (core/extract-content response)))))))

(deftest ^:integration test-core-streaming-openai
  (testing "OpenAI streaming integration test"
    (when (System/getenv "OPENAI_API_KEY")
      (let [request {:messages [{:role :user :content "Count to 3"}]
                     :stream true
                     :max-tokens 20
                     :api-key (System/getenv "OPENAI_API_KEY")}
            ch (core/completion :openai "gpt-3.5-turbo" request)]
        (is (some? ch))
        ;; Channel should be readable
        (is (instance? clojure.core.async.impl.channels.ManyToManyChannel ch))))))
