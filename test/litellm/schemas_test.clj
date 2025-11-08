(ns litellm.schemas-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.schemas :as schemas]))

;; ============================================================================
;; Embedding Request Schema Tests
;; ============================================================================

(deftest test-valid-embedding-request-single-input
  (testing "Valid embedding request with single string input"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"}]
      (is (true? (schemas/valid-embedding-request? request)))
      (is (nil? (schemas/explain-embedding-request request))))))

(deftest test-valid-embedding-request-array-input
  (testing "Valid embedding request with array input"
    (let [request {:model "text-embedding-3-small"
                  :input ["Hello world" "Goodbye world"]}]
      (is (true? (schemas/valid-embedding-request? request)))
      (is (nil? (schemas/explain-embedding-request request))))))

(deftest test-valid-embedding-request-with-optional-fields
  (testing "Valid embedding request with optional fields"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"
                  :encoding-format :float
                  :dimensions 1536
                  :user "test-user"
                  :timeout 5000
                  :input-type :query}]
      (is (true? (schemas/valid-embedding-request? request)))
      (is (nil? (schemas/explain-embedding-request request))))))

(deftest test-valid-embedding-request-with-api-config
  (testing "Valid embedding request with API config"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"
                  :api-key "sk-test"
                  :api-base "https://api.example.com"}]
      (is (true? (schemas/valid-embedding-request? request)))
      (is (nil? (schemas/explain-embedding-request request))))))

(deftest test-invalid-embedding-request-missing-model
  (testing "Invalid embedding request - missing model"
    (let [request {:input "Hello world"}]
      (is (false? (schemas/valid-embedding-request? request)))
      (is (some? (schemas/explain-embedding-request request))))))

(deftest test-invalid-embedding-request-missing-input
  (testing "Invalid embedding request - missing input"
    (let [request {:model "text-embedding-3-small"}]
      (is (false? (schemas/valid-embedding-request? request)))
      (is (some? (schemas/explain-embedding-request request))))))

(deftest test-invalid-embedding-request-wrong-encoding-format
  (testing "Invalid embedding request - wrong encoding format"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"
                  :encoding-format :invalid}]
      (is (false? (schemas/valid-embedding-request? request)))
      (is (some? (schemas/explain-embedding-request request))))))

(deftest test-invalid-embedding-request-wrong-input-type
  (testing "Invalid embedding request - wrong input type"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"
                  :input-type :invalid}]
      (is (false? (schemas/valid-embedding-request? request)))
      (is (some? (schemas/explain-embedding-request request))))))

;; ============================================================================
;; Embedding Response Schema Tests
;; ============================================================================

(deftest test-valid-embedding-response
  (testing "Valid embedding response"
    (let [response {:object "list"
                   :data [{:object "embedding"
                          :embedding [0.1 0.2 0.3]
                          :index 0}]
                   :model "text-embedding-3-small"
                   :usage {:prompt-tokens 10
                          :completion-tokens 0
                          :total-tokens 10}}]
      (is (true? (schemas/valid-embedding-response? response)))
      (is (nil? (schemas/explain-embedding-response response))))))

(deftest test-valid-embedding-response-multiple-embeddings
  (testing "Valid embedding response with multiple embeddings"
    (let [response {:object "list"
                   :data [{:object "embedding"
                          :embedding [0.1 0.2 0.3]
                          :index 0}
                          {:object "embedding"
                          :embedding [0.4 0.5 0.6]
                          :index 1}]
                   :model "text-embedding-3-small"
                   :usage {:prompt-tokens 20
                          :completion-tokens 0
                          :total-tokens 20}}]
      (is (true? (schemas/valid-embedding-response? response)))
      (is (nil? (schemas/explain-embedding-response response))))))

(deftest test-invalid-embedding-response-missing-data
  (testing "Invalid embedding response - missing data"
    (let [response {:object "list"
                   :model "text-embedding-3-small"
                   :usage {:prompt-tokens 10
                          :completion-tokens 0
                          :total-tokens 10}}]
      (is (false? (schemas/valid-embedding-response? response)))
      (is (some? (schemas/explain-embedding-response response))))))

(deftest test-invalid-embedding-response-missing-usage
  (testing "Invalid embedding response - missing usage"
    (let [response {:object "list"
                   :data [{:object "embedding"
                          :embedding [0.1 0.2 0.3]
                          :index 0}]
                   :model "text-embedding-3-small"}]
      (is (false? (schemas/valid-embedding-response? response)))
      (is (some? (schemas/explain-embedding-response response))))))

(deftest test-invalid-embedding-response-wrong-embedding-type
  (testing "Invalid embedding response - wrong embedding type"
    (let [response {:object "list"
                   :data [{:object "embedding"
                          :embedding "not-a-vector"
                          :index 0}]
                   :model "text-embedding-3-small"
                   :usage {:prompt-tokens 10
                          :completion-tokens 0
                          :total-tokens 10}}]
      (is (false? (schemas/valid-embedding-response? response)))
      (is (some? (schemas/explain-embedding-response response))))))

;; ============================================================================
;; Validation Functions Tests
;; ============================================================================

(deftest test-validate-embedding-request-valid
  (testing "validate-embedding-request! with valid request"
    (let [request {:model "text-embedding-3-small"
                  :input "Hello world"}]
      (is (= request (schemas/validate-embedding-request! request))))))

(deftest test-validate-embedding-request-invalid
  (testing "validate-embedding-request! with invalid request throws"
    (let [request {:input "Hello world"}]
      (is (thrown-with-msg? 
            clojure.lang.ExceptionInfo
            #"Invalid embedding request"
            (schemas/validate-embedding-request! request))))))

(deftest test-validate-embedding-response-valid
  (testing "validate-embedding-response! with valid response"
    (let [response {:object "list"
                   :data [{:object "embedding"
                          :embedding [0.1 0.2 0.3]
                          :index 0}]
                   :model "text-embedding-3-small"
                   :usage {:prompt-tokens 10
                          :completion-tokens 0
                          :total-tokens 10}}]
      (is (= response (schemas/validate-embedding-response! response))))))

(deftest test-validate-embedding-response-invalid
  (testing "validate-embedding-response! with invalid response throws"
    (let [response {:object "list"
                   :data []}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid embedding response"
            (schemas/validate-embedding-response! response))))))
