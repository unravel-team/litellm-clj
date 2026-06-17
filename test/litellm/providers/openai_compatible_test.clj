(ns litellm.providers.openai-compatible-test
  (:require [clojure.test :refer [are deftest is testing]]
            [litellm.providers.openai-compatible :as compat]
            [litellm.errors :as errors]))

(deftest kw->api-string-test
  (testing "keywords and strings become provider snake_case strings"
    (are [input expected] (= expected (compat/kw->api-string input))
      :json-object "json_object"
      :max "max"
      "json_object" "json_object"
      "json-object" "json_object"
      nil nil)))

(deftest transform-messages-test
  (testing "messages preserve provider-native assistant fields"
    (let [messages [{:role :system :content nil}
                    {:role "assistant"
                     :content [{:type "text" :text "use prior thoughts"}]
                     :tool-calls [{:id "call_1"
                                   :type "function"
                                   :function {:name "lookup"
                                              :arguments "{}"}}]
                     :reasoning-content "kept reasoning"
                     :partial true}
                    {:role :tool
                     :content "done"
                     :tool-call-id "call_1"}]
          transformed (compat/transform-messages messages)]
      (is (vector? transformed))
      (is (= {:role "system" :content nil}
             (first transformed)))
      (is (= "kept reasoning" (get-in transformed [1 :reasoning_content])))
      (is (= [{:type "text" :text "use prior thoughts"}]
             (get-in transformed [1 :content])))
      (is (= "call_1" (get-in transformed [2 :tool_call_id])))
      (is (= true (get-in transformed [1 :partial]))))))

(deftest transform-tools-test
  (testing "canonical tool shape is preserved"
    (let [tool {:type "function"
                :function {:name "weather"
                           :description "Get weather"
                           :parameters {:type "object"}
                           :strict true}}
          transformed (compat/transform-tools [tool])]
      (is (= [{:type "function"
               :function {:name "weather"
                          :description "Get weather"
                          :parameters {:type "object"}
                          :strict true}}]
             transformed))))
  (testing "legacy tool shape is normalized"
    (let [tool {:tool-type "function"
                :function {:function-name "weather"
                           :function-description "Get weather"
                           :function-parameters {:type "object"}}}
          transformed (compat/transform-tools [tool])]
      (is (= "function" (get-in transformed [0 :type])))
      (is (= "weather" (get-in transformed [0 :function :name])))
      (is (= {:type "object"} (get-in transformed [0 :function :parameters]))))))

(deftest transform-tool-choice-test
  (testing "tool choice keywords become strings and maps pass through"
    (is (= "auto" (compat/transform-tool-choice :auto)))
    (is (= {:type "function" :function {:name "weather"}}
           (compat/transform-tool-choice {:type "function" :function {:name "weather"}})))))

(deftest merge-extra-body-test
  (testing "extra body merges vendor fields after known fields"
    (is (= {:model "m"
            :messages []
            :temperature 0.2
            :custom_flag true}
           (compat/merge-extra-body {:model "m" :messages []}
                                    {:temperature 0.2}
                                    {:custom_flag true}))))
  (testing "extra body cannot override protected request keys"
    (try
      (compat/merge-extra-body {:model "m" :messages []}
                               nil
                               {:model "override" :stream true})
      (is false "expected invalid request")
      (catch clojure.lang.ExceptionInfo e
        (is (errors/error-type? e :litellm/invalid-request))
        (is (= #{:model :stream}
               (set (get-in (ex-data e)
                            [:context :validation-errors :protected-keys]))))))))

(deftest transform-response-test
  (testing "standard response extracts reasoning, tools, and cache usage"
    (let [response {:body {:id "chatcmpl-1"
                           :object "chat.completion"
                           :created 123
                           :model "m"
                           :choices [{:index 0
                                      :message {:role "assistant"
                                                :content "hi"
                                                :reasoning_content "hidden"
                                                :tool_calls [{:id "call_1"
                                                              :type "function"
                                                              :function {:name "weather"
                                                                         :arguments "{}"}}]}
                                      :finish_reason "tool_calls"}]
                           :usage {:prompt_tokens 10
                                   :completion_tokens 4
                                   :total_tokens 14
                                   :prompt_tokens_details {:cached_tokens 7}}}}
          transformed (compat/transform-response response)]
      (is (= :tool_calls (get-in transformed [:choices 0 :finish-reason])))
      (is (= "hidden" (get-in transformed [:choices 0 :message :reasoning-content])))
      (is (= "weather" (get-in transformed [:choices 0 :message :tool-calls 0 :function :name])))
      (is (= 7 (get-in transformed [:usage :cached-tokens])))
      (is (= {:cached_tokens 7}
             (get-in transformed [:usage :prompt-tokens-details])))))
  (testing "usage.cached_tokens is also supported"
    (is (= 3
           (-> {:body {:usage {:prompt_tokens 5
                               :completion_tokens 2
                               :total_tokens 7
                               :cached_tokens 3}}}
               compat/transform-response
               (get-in [:usage :cached-tokens]))))))

(deftest transform-streaming-chunk-test
  (testing "streaming chunks preserve reasoning and partial tool call deltas"
    (let [chunk {:id "chunk-1"
                 :object "chat.completion.chunk"
                 :created 123
                 :model "m"
                 :choices [{:index 0
                            :delta {:role "assistant"
                                    :content "h"
                                    :reasoning_content "r"
                                    :tool_calls [{:index 0
                                                  :id "call_1"
                                                  :type "function"
                                                  :function {:name "weather"
                                                             :arguments "{"}}]}
                            :finish_reason nil}]}
          transformed (compat/transform-streaming-chunk chunk)]
      (is (= :assistant (get-in transformed [:choices 0 :delta :role])))
      (is (= "r" (get-in transformed [:choices 0 :delta :reasoning-content])))
      (is (= "{" (get-in transformed [:choices 0 :delta :tool-calls 0 :function :arguments]))))))
