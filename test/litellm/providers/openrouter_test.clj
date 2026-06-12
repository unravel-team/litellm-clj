(ns litellm.providers.openrouter-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.openrouter :as openrouter]))

(def ^:private response-fixture
  {:body {:id "gen-1"
          :object "chat.completion"
          :created 1
          :model "google/gemini-3.5-flash"
          :choices [{:index 0
                     :message {:role "assistant"
                               :content "hi"
                               :tool_calls [{:id "c1"
                                             :type "function"
                                             :function {:name "f"
                                                        :arguments "{}"}}]}
                     :finish_reason "tool_calls"}]
          :usage {:prompt_tokens 1 :completion_tokens 2 :total_tokens 3}}})

(deftest test-transform-response
  (testing "Transform OpenRouter response to standard format"
    (let [transformed (openrouter/transform-response-impl :openrouter response-fixture)]
      (is (= "gen-1" (:id transformed)))
      (is (= :tool_calls (get-in transformed [:choices 0 :finish-reason])))
      (is (= "hi" (get-in transformed [:choices 0 :message :content]))))))

(deftest test-response-collections-are-vectors
  (testing "Indexed access works on :choices and :tool-calls"
    (let [transformed (openrouter/transform-response-impl :openrouter response-fixture)]
      (is (vector? (:choices transformed)))
      (is (vector? (get-in transformed [:choices 0 :message :tool-calls])))
      (is (= "f" (get-in transformed
                         [:choices 0 :message :tool-calls 0 :function :name]))))))

(deftest test-transform-tool-calls-vector
  (testing "transform-tool-calls returns a vector"
    (is (vector? (openrouter/transform-tool-calls
                  [{:id "c1" :type "function"
                    :function {:name "f" :arguments "{}"}}])))))
