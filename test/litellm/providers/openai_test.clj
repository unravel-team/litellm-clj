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

(deftest test-get-cost-per-token-gpt5-mini
  (testing "Get cost for GPT-5 mini"
    (let [cost (openai/get-cost-per-token-impl :openai "gpt-5-mini")]
      (is (some? cost))
      (is (= 0.00000025 (:input cost)))
      (is (= 0.000002 (:output cost))))))

(deftest test-get-cost-per-token-gpt5-nano
  (testing "Get cost for GPT-5 nano"
    (let [cost (openai/get-cost-per-token-impl :openai "gpt-5-nano")]
      (is (some? cost))
      (is (= 0.00000005 (:input cost)))
      (is (= 0.0000004 (:output cost))))))

(deftest test-get-cost-per-token-gpt5-pro
  (testing "Get cost for GPT-5 pro"
    (let [cost (openai/get-cost-per-token-impl :openai "gpt-5-pro")]
      (is (some? cost))
      (is (= 0.00000125 (:input cost)))
      (is (= 0.00001 (:output cost))))))

(deftest test-transform-request-gpt5-mini
  (testing "Transform request with GPT-5 mini model"
    (let [request {:model "gpt-5-mini"
                  :messages [{:role :user :content "Hello"}]
                  :max-tokens 100}
          config {}
          transformed (openai/transform-request-impl :openai request config)]
      (is (= "gpt-5-mini" (:model transformed)))
      (is (= 100 (:max_tokens transformed)))
      (is (= 1 (count (:messages transformed)))))))

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
