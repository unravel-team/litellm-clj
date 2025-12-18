(ns litellm.providers.azure-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.azure :as azure]))

;; ============================================================================
;; Message Transformation Tests
;; ============================================================================

(deftest test-transform-messages
  (testing "Transform messages to Azure OpenAI format"
    (let [messages [{:role :user :content "Hello"}
                   {:role :assistant :content "Hi there"}]
          transformed (azure/transform-messages messages)]
      (is (= 2 (count transformed)))
      (is (= "user" (:role (first transformed))))
      (is (= "Hello" (:content (first transformed)))))))

(deftest test-transform-tools
  (testing "Transform tools to Azure OpenAI format"
    (let [tools [{:function {:name "get_weather"
                            :description "Get weather"}}]
          transformed (azure/transform-tools tools)]
      (is (= 1 (count transformed)))
      (is (= "get_weather" (get-in (first transformed) [:function :name]))))))

(deftest test-transform-message
  (testing "Transform Azure OpenAI message to standard format"
    (let [message {:role "assistant" :content "Hello"}
          transformed (azure/transform-message message)]
      (is (= :assistant (:role transformed)))
      (is (= "Hello" (:content transformed))))))

(deftest test-transform-usage
  (testing "Transform usage information"
    (let [usage {:prompt_tokens 10
                :completion_tokens 20
                :total_tokens 30}
          transformed (azure/transform-usage usage)]
      (is (= 10 (:prompt-tokens transformed)))
      (is (= 20 (:completion-tokens transformed)))
      (is (= 30 (:total-tokens transformed))))))

;; ============================================================================
;; URL Building Tests
;; ============================================================================

(deftest test-build-chat-url
  (testing "Build chat completions URL with default API version"
    (let [config {:api-base "https://my-resource.openai.azure.com"
                 :deployment "gpt-4-deployment"}
          url (azure/build-chat-url config)]
      (is (= "https://my-resource.openai.azure.com/openai/deployments/gpt-4-deployment/chat/completions?api-version=2024-10-21" url))))

  (testing "Build chat completions URL with custom API version"
    (let [config {:api-base "https://my-resource.openai.azure.com"
                 :deployment "gpt-4-deployment"
                 :api-version "2024-06-01"}
          url (azure/build-chat-url config)]
      (is (= "https://my-resource.openai.azure.com/openai/deployments/gpt-4-deployment/chat/completions?api-version=2024-06-01" url)))))

(deftest test-build-embedding-url
  (testing "Build embeddings URL"
    (let [config {:api-base "https://my-resource.openai.azure.com"
                 :deployment "embedding-deployment"}
          url (azure/build-embedding-url config)]
      (is (= "https://my-resource.openai.azure.com/openai/deployments/embedding-deployment/embeddings?api-version=2024-10-21" url)))))

;; ============================================================================
;; Request Transformation Tests
;; ============================================================================

(deftest test-transform-request-basic
  (testing "Transform basic request - model not in body for Azure"
    (let [request {:model "gpt-4"
                  :messages [{:role :user :content "Hello"}]
                  :max-tokens 100}
          config {}
          transformed (azure/transform-request-impl :azure request config)]
      ;; Azure doesn't include model in body - it's determined by deployment
      (is (not (contains? transformed :model)))
      (is (= 100 (:max_tokens transformed)))
      (is (= 1 (count (:messages transformed)))))))

(deftest test-transform-request-with-all-params
  (testing "Transform request with all optional parameters"
    (let [request {:model "gpt-4"
                  :messages [{:role :user :content "Hello"}]
                  :max-tokens 100
                  :temperature 0.7
                  :top-p 0.9
                  :frequency-penalty 0.5
                  :presence-penalty 0.5
                  :stop ["END"]}
          config {}
          transformed (azure/transform-request-impl :azure request config)]
      (is (= 100 (:max_tokens transformed)))
      (is (= 0.7 (:temperature transformed)))
      (is (= 0.9 (:top_p transformed)))
      (is (= 0.5 (:frequency_penalty transformed)))
      (is (= 0.5 (:presence_penalty transformed)))
      (is (= ["END"] (:stop transformed))))))

;; ============================================================================
;; Capability Tests
;; ============================================================================

(deftest test-supports-streaming
  (testing "Azure OpenAI supports streaming"
    (is (true? (azure/supports-streaming-impl :azure)))))

(deftest test-supports-function-calling
  (testing "Azure OpenAI supports function calling"
    (is (true? (azure/supports-function-calling-impl :azure)))))

(deftest test-supports-embeddings
  (testing "Azure OpenAI supports embeddings"
    (is (true? (azure/supports-embeddings-impl :azure)))))

;; ============================================================================
;; Error Handling Tests
;; ============================================================================

(deftest test-handle-error-response-401
  (testing "Handle 401 authentication error"
    (let [response {:status 401 :body {:error {:message "Invalid API key"}}}]
      (is (thrown-with-msg? Exception #"Invalid API key"
                            (azure/handle-error-response :azure response))))))

(deftest test-handle-error-response-429
  (testing "Handle 429 rate limit error"
    (let [response {:status 429
                   :body {:error {:message "Rate limit exceeded"}}
                   :headers {"retry-after" "60"}}]
      (is (thrown-with-msg? Exception #"Rate limit exceeded"
                            (azure/handle-error-response :azure response))))))

;; ============================================================================
;; Cost Tests
;; ============================================================================

(deftest test-get-cost-per-token
  (testing "Get cost for known model"
    (let [cost (azure/get-cost-per-token-impl :azure "gpt-4")]
      (is (some? cost))
      (is (contains? cost :input))
      (is (contains? cost :output))))

  (testing "Get cost for unknown model returns zeros"
    (let [cost (azure/get-cost-per-token-impl :azure "unknown-model")]
      (is (= {:input 0.0 :output 0.0} cost)))))

;; ============================================================================
;; Embedding Tests
;; ============================================================================

(deftest test-transform-embedding-request-single-input
  (testing "Transform embedding request with single string input"
    (let [request {:model "text-embedding-ada-002"
                  :input "Hello world"}
          config {}
          transformed (azure/transform-embedding-request-impl :azure request config)]
      (is (vector? (:input transformed)))
      (is (= 1 (count (:input transformed))))
      (is (= "Hello world" (first (:input transformed)))))))

(deftest test-transform-embedding-request-array-input
  (testing "Transform embedding request with array input"
    (let [request {:model "text-embedding-ada-002"
                  :input ["Hello" "World"]}
          config {}
          transformed (azure/transform-embedding-request-impl :azure request config)]
      (is (vector? (:input transformed)))
      (is (= 2 (count (:input transformed))))
      (is (= "Hello" (first (:input transformed))))
      (is (= "World" (second (:input transformed)))))))

(deftest test-transform-embedding-response
  (testing "Transform embedding response from Azure OpenAI format"
    (let [response {:body {:object "list"
                          :data [{:object "embedding"
                                 :embedding [0.1 0.2 0.3]
                                 :index 0}
                                {:object "embedding"
                                 :embedding [0.4 0.5 0.6]
                                 :index 1}]
                          :model "text-embedding-ada-002"
                          :usage {:prompt_tokens 10
                                 :completion_tokens 0
                                 :total_tokens 10}}}
          transformed (azure/transform-embedding-response-impl :azure response)]
      (is (= "list" (:object transformed)))
      (is (= "text-embedding-ada-002" (:model transformed)))
      (is (= 2 (count (:data transformed))))
      (is (= [0.1 0.2 0.3] (:embedding (first (:data transformed)))))
      (is (= [0.4 0.5 0.6] (:embedding (second (:data transformed)))))
      (is (= 10 (get-in transformed [:usage :prompt-tokens])))
      (is (= 0 (get-in transformed [:usage :completion-tokens]))))))

(deftest test-embedding-cost-map
  (testing "Embedding cost map contains known models"
    (is (contains? azure/default-embedding-cost-map "text-embedding-ada-002"))
    (is (contains? azure/default-embedding-cost-map "text-embedding-3-small"))
    (is (contains? azure/default-embedding-cost-map "text-embedding-3-large"))

    (testing "Costs have correct structure"
      (let [cost (get azure/default-embedding-cost-map "text-embedding-ada-002")]
        (is (contains? cost :input))
        (is (contains? cost :output))
        (is (number? (:input cost)))
        (is (zero? (:output cost)))))))

;; ============================================================================
;; Config Validation Tests
;; ============================================================================

(deftest test-validate-config
  (testing "Valid config passes validation"
    (let [config {:api-base "https://my-resource.openai.azure.com"
                 :deployment "gpt-4"
                 :api-key "test-key"}]
      (is (= config (azure/validate-config config)))))

  (testing "Missing api-base throws"
    (let [config {:deployment "gpt-4" :api-key "test-key"}]
      (is (thrown? Exception (azure/validate-config config)))))

  (testing "Missing deployment throws"
    (let [config {:api-base "https://my-resource.openai.azure.com" :api-key "test-key"}]
      (is (thrown? Exception (azure/validate-config config)))))

  (testing "Missing api-key throws"
    (let [config {:api-base "https://my-resource.openai.azure.com" :deployment "gpt-4"}]
      (is (thrown? Exception (azure/validate-config config))))))
