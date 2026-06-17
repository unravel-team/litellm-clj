(ns litellm.specs-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.specs :as specs]))

(def base-completion-request
  {:model "provider-model"
   :messages [{:role :user :content "Hello"}]})

(deftest test-spec-message-content-and-provider-fields
  (testing "Spec accepts provider message fields and request passthrough fields"
    (let [request {:model "provider-model"
                   :messages [{:role :system :content nil}
                              {:role :user
                               :content [{:type "text" :text "Describe image"}
                                         {:type "file_url" :file_url {:url "file://prompt.txt"}}]}
                              {:role :assistant
                               :content nil
                               :reasoning-content "thinking trace"
                               :tool-calls [{:id "call_123"
                                             :type "function"
                                             :function {:name "lookup"
                                                        :arguments "{}"}}]
                               :partial true}]
                   :response-format {:type :json-object}
                   :stream-options {:include-usage true}
                   :do-sample true
                   :tool-stream false
                   :prompt-cache-key "cache"
                   :safety-identifier "safe-user"
                   :request-id "req-123"
                   :user-id "user-123"
                   :extra-body {:vendor_field "value"}}]
      (is (true? (specs/valid-request? request))))))

(deftest test-spec-tool-shapes
  (testing "Spec accepts canonical and legacy tool declarations"
    (is (true? (specs/valid-request?
                (assoc base-completion-request
                       :tools [{:type "function"
                                :function {:name "lookup"
                                           :description "Lookup information"
                                           :parameters {:type "object"}
                                           :strict true}}]))))
    (is (true? (specs/valid-request?
                (assoc base-completion-request
                       :tools [{:tool-type "function"
                                :function {:function-name "lookup"
                                           :function-description "Lookup information"
                                           :function-parameters {:type "object"}}}]))))))

(deftest test-spec-reasoning-and-thinking-shapes
  (testing "Spec accepts expanded reasoning and thinking values"
    (doseq [effort [:minimal :none :low :medium :high :xhigh :max]]
      (is (true? (specs/valid-request?
                  (assoc base-completion-request :reasoning-effort effort)))
          (str "Expected reasoning effort " effort " to validate")))
    (doseq [thinking [{:type :enabled}
                      {:type :disabled}
                      {:type :enabled :budget-tokens 1024}
                      {:type :enabled :keep :all}
                      {:type :disabled :clear-thinking false}]]
      (is (true? (specs/valid-request?
                  (assoc base-completion-request :thinking thinking)))
          (str "Expected thinking config " thinking " to validate")))))
