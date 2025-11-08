(ns litellm.providers.openai-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.openai :as openai]))

;; ============================================================================
;; Message Transformation Tests
;; ============================================================================

(deftest test-transform-messages
  (testing "Transform messages to OpenAI format"
    (let [messages [{:role :user :content "Hello"}
                   {:role :assistant :content "Hi there"}]
          transformed (openai/transform-messages messages)]
      (is (= 2 (count transformed)))
      (is (= "user" (:role (first transformed))))
      (is (= "Hello" (:content (first transformed)))))))

(deftest test-transform-tools
  (testing "Transform tools to OpenAI format"
    (let [tools [{:function {:name "get_weather"
                            :description "Get weather"}}]
          transformed (openai/transform-tools tools)]
      (is (= 1 (count transformed)))
      (is (= "get_weather" (get-in (first transformed) [:function :name]))))))

(deftest test-transform-message
  (testing "Transform OpenAI message to standard format"
    (let [message {:role "assistant" :content "Hello"}
          transformed (openai/transform-message message)]
      (is (= :assistant (:role transformed)))
      (is (= "Hello" (:content transformed))))))

(deftest test-transform-usage
  (testing "Transform usage information"
    (let [usage {:prompt_tokens 10
                :completion_tokens 20
                :total_tokens 30}
          transformed (openai/transform-usage usage)]
      (is (= 10 (:prompt-tokens transformed)))
      (is (= 20 (:completion-tokens transformed)))
      (is (= 30 (:total-tokens transformed))))))

(deftest test-transform-request-basic
  (testing "Transform basic request"
    (let [request {:model "gpt-4"
                  :messages [{:role :user :content "Hello"}]
                  :max-tokens 100}
          config {}
          transformed (openai/transform-request-impl :openai request config)]
      (is (= "gpt-4" (:model transformed)))
      (is (= 100 (:max_tokens transformed)))
      (is (= 1 (count (:messages transformed)))))))

(deftest test-get-cost-per-token
  (testing "Get cost for known model"
    (let [cost (openai/get-cost-per-token-impl :openai "gpt-4")]
      (is (some? cost))
      (is (contains? cost :input))
      (is (contains? cost :output)))))

(deftest test-supports-streaming
  (testing "OpenAI supports streaming"
    (is (true? (openai/supports-streaming-impl :openai)))))

(deftest test-supports-function-calling
  (testing "OpenAI supports function calling"
    (is (true? (openai/supports-function-calling-impl :openai)))))

(deftest test-handle-error-response-401
  (testing "Handle 401 authentication error"
    (let [response {:status 401 :body {:error {:message "Invalid API key"}}}]
      (is (thrown-with-msg? Exception #"Invalid API key"
                            (openai/handle-error-response :openai response))))))

(deftest test-handle-error-response-429
  (testing "Handle 429 rate limit error"
    (let [response {:status 429 
                   :body {:error {:message "Rate limit exceeded"}}
                   :headers {"retry-after" "60"}}]
      (is (thrown-with-msg? Exception #"Rate limit exceeded"
                            (openai/handle-error-response :openai response))))))

;; ============================================================================
;; Embedding Tests
;; ============================================================================

(deftest test-transform-embedding-request-single-input
  (testing "Transform embedding request with single string input"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"}
          config {}
          transformed (openai/transform-embedding-request-impl :openai request config)]
      (is (= "text-embedding-3-small" (:model transformed)))
      (is (vector? (:input transformed)))
      (is (= 1 (count (:input transformed))))
      (is (= "Hello world" (first (:input transformed)))))))

(deftest test-transform-embedding-request-array-input
  (testing "Transform embedding request with array input"
    (let [request {:model "text-embedding-3-small"
                  :input ["Hello" "World"]}
          config {}
          transformed (openai/transform-embedding-request-impl :openai request config)]
      (is (= "text-embedding-3-small" (:model transformed)))
      (is (vector? (:input transformed)))
      (is (= 2 (count (:input transformed))))
      (is (= "Hello" (first (:input transformed))))
      (is (= "World" (second (:input transformed)))))))

(deftest test-transform-embedding-request-with-optional-fields
  (testing "Transform embedding request with optional fields"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"
                  :encoding-format :float
                  :dimensions 1536
                  :user "test-user"}
          config {}
          transformed (openai/transform-embedding-request-impl :openai request config)]
      (is (= "text-embedding-3-small" (:model transformed)))
      (is (= "float" (:encoding_format transformed)))
      (is (= 1536 (:dimensions transformed)))
      (is (= "test-user" (:user transformed))))))

(deftest test-transform-embedding-response
  (testing "Transform embedding response from OpenAI format"
    (let [response {:body {:object "list"
                          :data [{:object "embedding"
                                 :embedding [0.1 0.2 0.3]
                                 :index 0}
                                {:object "embedding"
                                 :embedding [0.4 0.5 0.6]
                                 :index 1}]
                          :model "text-embedding-3-small"
                          :usage {:prompt_tokens 10
                                 :completion_tokens 0
                                 :total_tokens 10}}}
          transformed (openai/transform-embedding-response-impl :openai response)]
      (is (= "list" (:object transformed)))
      (is (= "text-embedding-3-small" (:model transformed)))
      (is (= 2 (count (:data transformed))))
      (is (= [0.1 0.2 0.3] (:embedding (first (:data transformed)))))
      (is (= [0.4 0.5 0.6] (:embedding (second (:data transformed)))))
      (is (= 10 (get-in transformed [:usage :prompt-tokens])))
      (is (= 0 (get-in transformed [:usage :completion-tokens]))))))

(deftest test-supports-embeddings
  (testing "OpenAI supports embeddings"
    (is (true? (openai/supports-embeddings-impl :openai)))))

(deftest test-embedding-cost-map
  (testing "Embedding cost map contains known models"
    (is (contains? openai/default-embedding-cost-map "text-embedding-3-small"))
    (is (contains? openai/default-embedding-cost-map "text-embedding-3-large"))
    (is (contains? openai/default-embedding-cost-map "text-embedding-ada-002"))
    
    (testing "Costs have correct structure"
      (let [cost (get openai/default-embedding-cost-map "text-embedding-3-small")]
        (is (contains? cost :input))
        (is (contains? cost :output))
        (is (number? (:input cost)))
        (is (zero? (:output cost)))))))
