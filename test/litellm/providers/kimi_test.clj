(ns litellm.providers.kimi-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.errors :as errors]
            [litellm.providers.core :as providers]
            [litellm.providers.kimi :as kimi]))

(defn- thrown-invalid-request?
  [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (errors/error-type? e :litellm/invalid-request))))

(deftest url-building-test
  (testing "Kimi endpoint URLs tolerate trailing slash overrides"
    (is (= "https://api.moonshot.ai/v1/chat/completions"
           (kimi/chat-url {})))
    (is (= "https://example.test/v1/chat/completions"
           (kimi/chat-url {:api-base "https://example.test/v1/"})))
    (is (= "https://api.moonshot.ai/v1/models"
           (kimi/models-url {})))))

(deftest transform-request-basic-test
  (testing "basic request uses Kimi chat fields"
    (let [request {:model "kimi-k2.6"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 128}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (= "kimi-k2.6" (:model transformed)))
      (is (= [{:role "user" :content "Hello"}] (:messages transformed)))
      (is (= 128 (:max_completion_tokens transformed)))
      (is (not (contains? transformed :max_tokens))))))

(deftest transform-request-optional-params-test
  (testing "optional params become Kimi snake_case fields"
    (let [request {:model "kimi-k2.6"
                   :messages [{:role :user :content "Return JSON"}]
                   :temperature 0.1
                   :top-p 0.9
                   :frequency-penalty 0.2
                   :presence-penalty 0.3
                   :stop ["END"]
                   :stream true
                   :response-format {:type :json-schema
                                     :json-schema {:name "answer"
                                                   :schema {:type "object"}}}
                   :prompt-cache-key "cache-1"
                   :safety-identifier "user-1"
                   :stream-options {:include-usage true}
                   :extra-body {:metadata {:trace "abc"}}}
          transformed (kimi/transform-request-impl :kimi request {})
          expected {:temperature 0.1
                    :top_p 0.9
                    :frequency_penalty 0.2
                    :presence_penalty 0.3
                    :stop ["END"]
                    :stream true
                    :response_format {:type "json_schema"
                                      :json_schema {:name "answer"
                                                    :schema {:type "object"}}}
                    :prompt_cache_key "cache-1"
                    :safety_identifier "user-1"
                    :stream_options {:include_usage true}
                    :metadata {:trace "abc"}}]
      (is (= expected (select-keys transformed (keys expected))))))
  (testing "extra-body cannot override protected fields"
    (is (thrown-invalid-request?
         #(kimi/transform-request-impl :kimi
                                       {:model "kimi-k2.6"
                                        :messages [{:role :user :content "hi"}]
                                        :extra-body {:model "override"}}
                                       {})))))

(deftest transform-request-thinking-test
  (testing "K2.6 thinking config maps to Kimi thinking object"
    (let [request {:model "kimi-k2.6"
                   :messages [{:role :user :content "Think"}]
                   :thinking {:type :enabled :keep :all}}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (= {:type "enabled" :keep "all"}
             (:thinking transformed)))))
  (testing "K2.5 thinking disabled is allowed"
    (let [request {:model "kimi-k2.5"
                   :messages [{:role :user :content "No thinking"}]
                   :thinking {:type :disabled}}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (= {:type "disabled"} (:thinking transformed)))))
  (testing "K2.7 code omits thinking when caller omits it"
    (let [request {:model "kimi-k2.7-code"
                   :messages [{:role :user :content "Code"}]}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (not (contains? transformed :thinking)))))
  (testing "K2.7 code allows enabled thinking with keep all"
    (let [request {:model "kimi-k2.7-code-highspeed"
                   :messages [{:role :user :content "Code"}]
                   :thinking {:type :enabled :keep :all}}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (= {:type "enabled" :keep "all"} (:thinking transformed)))))
  (testing "K2.7 code rejects disabled thinking before HTTP"
    (is (thrown-invalid-request?
         #(kimi/transform-request-impl :kimi
                                       {:model "kimi-k2.7-code"
                                        :messages [{:role :user :content "Code"}]
                                        :thinking {:type :disabled}}
                                       {}))))
  (testing "invalid thinking keep value fails before HTTP"
    (is (thrown-invalid-request?
         #(kimi/transform-request-impl :kimi
                                       {:model "kimi-k2.6"
                                        :messages [{:role :user :content "Think"}]
                                        :thinking {:type :enabled :keep :brief}}
                                       {})))))

(deftest transform-request-tools-and-messages-test
  (testing "canonical tools preserve strict and assistant partial passes through"
    (let [request {:model "kimi-k2.6"
                   :messages [{:role :assistant
                               :content "{"
                               :partial true}
                              {:role :user
                               :content [{:type "text" :text "weather"}
                                         {:type "image_url"
                                          :image_url {:url "https://example.test/a.png"}}]}]
                   :tools [{:type "function"
                            :function {:name "weather"
                                       :description "Get weather"
                                       :parameters {:type "object"}
                                       :strict true}}]
                   :tool-choice :auto}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (true? (get-in transformed [:messages 0 :partial])))
      (is (= [{:type "text" :text "weather"}
              {:type "image_url"
               :image_url {:url "https://example.test/a.png"}}]
             (get-in transformed [:messages 1 :content])))
      (is (= "function" (get-in transformed [:tools 0 :type])))
      (is (= "weather" (get-in transformed [:tools 0 :function :name])))
      (is (= true (get-in transformed [:tools 0 :function :strict])))
      (is (= "auto" (:tool_choice transformed)))))
  (testing "legacy tools are normalized"
    (let [request {:model "kimi-k2.6"
                   :messages [{:role :user :content "weather"}]
                   :tools [{:tool-type "function"
                            :function {:function-name "weather"
                                       :function-description "Get weather"
                                       :function-parameters {:type "object"}}}]}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (= "weather" (get-in transformed [:tools 0 :function :name])))
      (is (= {:type "object"} (get-in transformed [:tools 0 :function :parameters]))))))

(deftest transform-request-preserves-reasoning-content-test
  (testing "caller-provided assistant reasoning content is preserved outbound"
    (let [request {:model "kimi-k2.6"
                   :messages [{:role :assistant
                               :content "Prior answer"
                               :reasoning-content "prior hidden reasoning"
                               :tool-calls [{:id "call_1"
                                             :type "function"
                                             :function {:name "lookup"
                                                        :arguments "{}"}}]}]}
          transformed (kimi/transform-request-impl :kimi request {})]
      (is (= "prior hidden reasoning"
             (get-in transformed [:messages 0 :reasoning_content])))
      (is (= "lookup"
             (get-in transformed [:messages 0 :tool_calls 0 :function :name]))))))

(deftest transform-response-test
  (testing "response extracts reasoning content, tool calls, and cached usage"
    (let [response {:body {:id "chatcmpl-1"
                           :object "chat.completion"
                           :created 123
                           :model "kimi-k2.6"
                           :choices [{:index 0
                                      :message {:role "assistant"
                                                :content "Answer"
                                                :reasoning_content "Hidden"
                                                :tool_calls [{:id "call_1"
                                                              :type "function"
                                                              :function {:name "lookup"
                                                                         :arguments "{}"}}]}
                                      :finish_reason "tool_calls"}]
                           :usage {:prompt_tokens 10
                                   :completion_tokens 5
                                   :total_tokens 15
                                   :cached_tokens 4}}}
          transformed (kimi/transform-response-impl :kimi response)]
      (is (= "Hidden" (get-in transformed [:choices 0 :message :reasoning-content])))
      (is (= "lookup" (get-in transformed [:choices 0 :message :tool-calls 0 :function :name])))
      (is (= :tool_calls (get-in transformed [:choices 0 :finish-reason])))
      (is (= 4 (get-in transformed [:usage :cached-tokens]))))))

(deftest transform-streaming-chunk-test
  (testing "streaming chunk extracts reasoning content and tool-call deltas"
    (let [chunk {:id "chunk-1"
                 :object "chat.completion.chunk"
                 :created 124
                 :model "kimi-k2.6"
                 :choices [{:index 0
                            :delta {:role "assistant"
                                    :content "A"
                                    :reasoning_content "R"
                                    :tool_calls [{:index 0
                                                  :id "call_1"
                                                  :type "function"
                                                  :function {:name "lookup"
                                                             :arguments "{"}}]}
                            :finish_reason nil}]
                 :usage {:prompt_tokens 10
                         :completion_tokens 1
                         :total_tokens 11
                         :cached_tokens 3}}
          transformed (kimi/transform-streaming-chunk-impl :kimi chunk)]
      (is (= :assistant (get-in transformed [:choices 0 :delta :role])))
      (is (= "R" (get-in transformed [:choices 0 :delta :reasoning-content])))
      (is (= "{" (get-in transformed [:choices 0 :delta :tool-calls 0 :function :arguments])))
      (is (= 3 (get-in transformed [:usage :cached-tokens]))))))

(deftest cost-and-capability-test
  (testing "known and unknown cost maps"
    (is (= {:input 0.00000095 :output 0.000004 :cache-read 0.00000016}
           (kimi/get-cost-per-token-impl :kimi "kimi-k2.6")))
    (is (= {:input 0.0 :output 0.0}
           (kimi/get-cost-per-token-impl :kimi "unknown"))))
  (testing "capability helpers"
    (is (true? (kimi/supports-streaming-impl :kimi)))
    (is (true? (kimi/supports-function-calling-impl :kimi)))))

(deftest multimethod-registration-test
  (testing "Kimi provider is registered for chat completion multimethods"
    (is (true? (providers/provider-available? :kimi)))
    (is (true? (providers/supports-streaming? :kimi)))
    (is (true? (providers/supports-function-calling? :kimi)))
    (is (false? (providers/supports-embeddings? :kimi)))
    (is (= {:input 0.00000190 :output 0.000008 :cache-read 0.00000038}
           (providers/get-cost-per-token :kimi "kimi-k2.7-code-highspeed")))))
