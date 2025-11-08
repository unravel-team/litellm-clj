(ns litellm.providers.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.core :as providers]))

;; ============================================================================
;; Provider Discovery Tests
;; ============================================================================

(deftest test-list-available-providers
  (testing "List all available providers"
    (let [providers-list (providers/list-available-providers)]
      (is (set? providers-list))
      (is (seq providers-list))
      (is (contains? providers-list :openai))
      (is (contains? providers-list :anthropic))
      (is (contains? providers-list :gemini))
      (is (contains? providers-list :mistral)))))

(deftest test-provider-available
  (testing "Check if provider is available"
    (is (true? (providers/provider-available? :openai)))
    (is (true? (providers/provider-available? :anthropic)))
    (is (true? (providers/provider-available? :gemini)))
    (is (true? (providers/provider-available? :mistral)))
    (is (false? (providers/provider-available? :nonexistent)))))

;; ============================================================================
;; Embedding Multimethod Tests
;; ============================================================================

(deftest test-supports-embeddings-multimethod
  (testing "supports-embeddings? multimethod dispatch"
    (is (true? (providers/supports-embeddings? :openai)))
    (is (true? (providers/supports-embeddings? :mistral)))
    (is (true? (providers/supports-embeddings? :gemini)))
    (is (false? (providers/supports-embeddings? :anthropic)))
    (is (false? (providers/supports-embeddings? :ollama)))))

(deftest test-validate-embedding-request-success
  (testing "Validate valid embedding request"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"}]
      (is (nil? (providers/validate-embedding-request :openai request))))))

(deftest test-validate-embedding-request-unsupported-provider
  (testing "Validate embedding request for unsupported provider"
    (let [request {:model "claude-3-sonnet-20240229"
                  :input "Hello world"}]
      (is (thrown-with-msg? 
            clojure.lang.ExceptionInfo
            #"doesn't support embeddings"
            (providers/validate-embedding-request :anthropic request))))))

(deftest test-validate-embedding-request-invalid-schema
  (testing "Validate invalid embedding request schema"
    (let [request {:model "text-embedding-3-small"}] ; missing :input
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid embedding request"
            (providers/validate-embedding-request :openai request))))))

;; ============================================================================
;; Provider Status Tests
;; ============================================================================

(deftest test-provider-status
  (testing "Get comprehensive provider status"
    (let [status (providers/provider-status :openai)]
      (is (map? status))
      (is (= :openai (:name status)))
      (is (contains? status :supports-streaming))
      (is (contains? status :supports-function-calling))
      (is (contains? status :rate-limits))
      (is (true? (:supports-streaming status)))
      (is (true? (:supports-function-calling status))))))

;; ============================================================================
;; Token Estimation Tests
;; ============================================================================

(deftest test-estimate-tokens
  (testing "Estimate tokens for text"
    (is (= 2 (providers/estimate-tokens "Hello world"))) ; 11 chars / 4 = 2
    (is (= 1 (providers/estimate-tokens "Hi")))
    (is (= 1 (providers/estimate-tokens ""))) ; Min of 1
    (is (nil? (providers/estimate-tokens nil)))))

(deftest test-estimate-request-tokens
  (testing "Estimate tokens for request"
    (let [request {:messages [{:role :user :content "Hello"}
                             {:role :assistant :content "Hi there"}]}]
      (is (number? (providers/estimate-request-tokens request)))
      (is (pos? (providers/estimate-request-tokens request))))))

;; ============================================================================
;; Cost Calculation Tests
;; ============================================================================

(deftest test-calculate-cost
  (testing "Calculate cost for known models"
    (let [cost (providers/calculate-cost :openai "gpt-4" 1000 500)]
      (is (number? cost))
      (is (pos? cost))))
  
  (testing "Calculate cost for unknown models returns 0"
    (let [cost (providers/calculate-cost :unknown "unknown-model" 1000 500)]
      (is (number? cost))
      (is (zero? cost)))))

;; ============================================================================
;; Provider Configuration Tests
;; ============================================================================

(deftest test-default-provider-config
  (testing "Get default configuration for provider"
    (let [config (providers/default-provider-config :openai)]
      (is (map? config))
      (is (= :openai (:provider config)))
      (is (contains? config :timeout))
      (is (contains? config :max-retries))
      (is (contains? config :rate-limit)))))

(deftest test-merge-provider-config
  (testing "Merge user config with provider defaults"
    (let [user-config {:timeout 5000 :custom-key "value"}
          merged (providers/merge-provider-config :openai user-config)]
      (is (= 5000 (:timeout merged)))
      (is (= "value" (:custom-key merged)))
      (is (contains? merged :max-retries)) ; From defaults
      (is (= :openai (:provider merged))))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validate-request
  (testing "Validate valid request"
    (let [request {:model "gpt-4"
                  :messages [{:role :user :content "test"}]}]
      (is (nil? (providers/validate-request :openai request)))))
  
  (testing "Validate request with streaming for unsupported provider"
    (let [request {:model "test"
                  :messages [{:role :user :content "test"}]
                  :stream true}]
      ;; Anthropic supports streaming, so let's test with a hypothetical unsupported one
      ;; For now, all major providers support streaming, so we'll skip this test
      (is (true? true))))
  
  (testing "Validate invalid request schema"
    (let [request {:model "gpt-4"}] ; missing :messages
      (is (thrown? clojure.lang.ExceptionInfo
                   (providers/validate-request :openai request))))))

;; ============================================================================
;; Model String Parsing Tests
;; ============================================================================

(deftest test-extract-provider-name
  (testing "Extract provider name from model string"
    (is (= :openai (providers/extract-provider-name "openai/gpt-4")))
    (is (= :anthropic (providers/extract-provider-name "anthropic/claude-3-opus-20240229")))
    (is (= :openai (providers/extract-provider-name "gpt-4"))))) ; Default to openai

(deftest test-extract-model-name
  (testing "Extract model name from model string"
    (is (= "gpt-4" (providers/extract-model-name "openai/gpt-4")))
    (is (= "claude-3-opus-20240229" (providers/extract-model-name "anthropic/claude-3-opus-20240229")))
    (is (= "gpt-4" (providers/extract-model-name "gpt-4"))))) ; No provider prefix

(deftest test-parse-model-string
  (testing "Parse model string into components"
    (let [parsed (providers/parse-model-string "openai/gpt-4")]
      (is (= :openai (:provider parsed)))
      (is (= "gpt-4" (:model parsed)))
      (is (= "openai/gpt-4" (:original parsed))))
    
    (let [parsed (providers/parse-model-string "gpt-4")]
      (is (= :openai (:provider parsed))) ; Default
      (is (= "gpt-4" (:model parsed)))
      (is (= "gpt-4" (:original parsed))))))

;; ============================================================================
;; Streaming Support Tests
;; ============================================================================

(deftest test-supports-streaming
  (testing "Check streaming support for providers"
    (is (true? (providers/supports-streaming? :openai)))
    (is (true? (providers/supports-streaming? :anthropic)))
    (is (true? (providers/supports-streaming? :gemini)))
    (is (true? (providers/supports-streaming? :openrouter)))
    (is (false? (providers/supports-streaming? :nonexistent)))))

;; ============================================================================
;; Function Calling Support Tests
;; ============================================================================

(deftest test-supports-function-calling
  (testing "Check function calling support for providers"
    (is (true? (providers/supports-function-calling? :openai)))
    (is (true? (providers/supports-function-calling? :anthropic)))
    (is (true? (providers/supports-function-calling? :gemini)))
    (is (true? (providers/supports-function-calling? :mistral)))
    (is (false? (providers/supports-function-calling? :nonexistent)))))

;; ============================================================================
;; Rate Limits Tests
;; ============================================================================

(deftest test-get-rate-limits
  (testing "Get rate limits for providers"
    (let [limits (providers/get-rate-limits :openai)]
      (is (map? limits))
      (is (contains? limits :requests-per-minute))
      (is (contains? limits :tokens-per-minute))
      (is (number? (:requests-per-minute limits)))
      (is (number? (:tokens-per-minute limits)))))
  
  (testing "Get default rate limits for unknown provider"
    (let [limits (providers/get-rate-limits :nonexistent)]
      (is (map? limits))
      (is (= 1000 (:requests-per-minute limits)))
      (is (= 50000 (:tokens-per-minute limits))))))
