(ns litellm.schemas-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.schemas :as schemas]))

;; ============================================================================
;; Completion Request Schema Tests
;; ============================================================================

(def base-completion-request
  {:model "provider-model"
   :messages [{:role :user :content "Hello"}]})

(deftest test-completion-request-provider-fields-survive-transform
  (testing "OpenAI-compatible provider request fields validate and survive decode"
    (let [provider-keys [:response-format :stream-options :do-sample :tool-stream
                         :prompt-cache-key :safety-identifier :request-id :user-id
                         :extra-body]
          request (assoc base-completion-request
                         :response-format {:type :json-object}
                         :stream-options {:include-usage true}
                         :do-sample true
                         :tool-stream true
                         :prompt-cache-key "cache-key"
                         :safety-identifier "safe-user"
                         :request-id "req-123"
                         :user-id "user-123"
                         :extra-body {:custom_vendor_field "value"})
          transformed (schemas/transform-request request)]
      (is (true? (schemas/valid-request? request)))
      (is (= (select-keys request provider-keys)
             (select-keys transformed provider-keys))))))

(deftest test-completion-request-message-content-shapes
  (testing "Messages accept nil content, reasoning metadata, tool calls, partial, and content parts"
    (let [request {:model "provider-model"
                   :messages [{:role :system :content nil}
                              {:role :user
                               :content [{:type "text" :text "Describe image"}
                                         {:type "image_url"
                                          :image_url {:url "https://example.com/cat.png"}}]}
                              {:role :assistant
                               :content nil
                               :reasoning-content "I inspected the prompt."
                               :tool-calls [{:id "call_123"
                                             :type "function"
                                             :function {:name "lookup"
                                                        :arguments "{}"}}]
                               :partial true}]}]
      (is (true? (schemas/valid-request? request)))
      (is (= request (schemas/transform-request request))))))

(deftest test-completion-request-tool-shapes
  (testing "Canonical and existing tool shapes both validate"
    (let [canonical (assoc base-completion-request
                           :tools [{:type "function"
                                    :function {:name "lookup"
                                               :description "Lookup information"
                                               :parameters {:type "object"}
                                               :strict true}}])
          legacy (assoc base-completion-request
                        :tools [{:tool-type "function"
                                 :function {:function-name "lookup"
                                            :function-description "Lookup information"
                                            :function-parameters {:type "object"}}}])]
      (is (true? (schemas/valid-request? canonical)))
      (is (= (:tools canonical) (:tools (schemas/transform-request canonical))))
      (is (true? (schemas/valid-request? legacy)))
      (is (= (:tools legacy) (:tools (schemas/transform-request legacy)))))))

(deftest test-completion-request-reasoning-and-thinking-shapes
  (testing "Expanded reasoning efforts and thinking config validate"
    (doseq [effort [:minimal :none :low :medium :high :xhigh :max]]
      (is (true? (schemas/valid-request?
                  (assoc base-completion-request :reasoning-effort effort)))
          (str "Expected reasoning effort " effort " to validate")))
    (doseq [thinking [{:type :enabled}
                      {:type :disabled}
                      {:type :enabled :budget-tokens 1024}
                      {:type :enabled :keep :all}
                      {:type :disabled :clear-thinking false}]]
      (is (true? (schemas/valid-request?
                  (assoc base-completion-request :thinking thinking)))
          (str "Expected thinking config " thinking " to validate")))))


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
