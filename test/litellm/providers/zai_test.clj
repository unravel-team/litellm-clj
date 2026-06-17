(ns litellm.providers.zai-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.errors :as errors]
            [litellm.providers.core :as providers]
            [litellm.providers.zai :as zai]))

(defn- thrown-invalid-request?
  [f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo e
      (errors/error-type? e :litellm/invalid-request))))

(deftest url-building-test
  (testing "Z.AI endpoint URLs tolerate trailing slash overrides"
    (is (= "https://api.z.ai/api/paas/v4/chat/completions"
           (zai/chat-url {})))
    (is (= "https://example.test/api/coding/paas/v4/chat/completions"
           (zai/chat-url {:api-base "https://example.test/api/coding/paas/v4/"})))
    (is (= "https://api.z.ai/api/paas/v4/models"
           (zai/models-url {})))))

(deftest transform-request-basic-test
  (testing "basic request uses Z.AI chat fields"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 128}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= "glm-5.2" (:model transformed)))
      (is (= [{:role "user" :content "Hello"}] (:messages transformed)))
      (is (= 128 (:max_tokens transformed)))
      (is (not (contains? transformed :max_completion_tokens))))))

(deftest transform-request-optional-params-test
  (testing "optional params become Z.AI snake_case fields"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "Return JSON"}]
                   :temperature 0.1
                   :top-p 0.9
                   :frequency-penalty 0.2
                   :presence-penalty 0.3
                   :stop ["END"]
                   :stream true
                   :response-format {:type :json-object}
                   :do-sample false
                   :tool-stream true
                   :stream-options {:include-usage true}
                   :extra-body {:request_id "req-1"}}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= {:temperature 0.1
              :top_p 0.9
              :frequency_penalty 0.2
              :presence_penalty 0.3
              :stop ["END"]
              :stream true
              :response_format {:type "json_object"}
              :do_sample false
              :tool_stream true
              :stream_options {:include_usage true}
              :request_id "req-1"}
             (select-keys transformed [:temperature
                                       :top_p
                                       :frequency_penalty
                                       :presence_penalty
                                       :stop
                                       :stream
                                       :response_format
                                       :do_sample
                                       :tool_stream
                                       :stream_options
                                       :request_id])))))
  (testing "extra-body cannot override protected fields"
    (is (thrown-invalid-request?
         #(zai/transform-request-impl :zai
                                      {:model "glm-5.2"
                                       :messages [{:role :user :content "hi"}]
                                       :extra-body {:model "override"}}
                                      {})))))

(deftest transform-request-thinking-test
  (testing "thinking config maps to Z.AI thinking object"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "Think"}]
                   :thinking {:type :enabled :clear-thinking false}}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= {:type "enabled" :clear_thinking false}
             (:thinking transformed)))))
  (testing "top-level clear-thinking convenience is nested"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "Preserve"}]
                   :clear-thinking false}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= {:clear_thinking false} (:thinking transformed)))))
  (testing "nested thinking clear-thinking wins over top-level convenience"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "Clear"}]
                   :thinking {:type :enabled :clear-thinking true}
                   :clear-thinking false}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= {:type "enabled" :clear_thinking true}
             (:thinking transformed)))))
  (testing "reasoning-effort maps to provider-accepted strings"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "Think hard"}]
                   :reasoning-effort :xhigh}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= "xhigh" (:reasoning_effort transformed))))))

(deftest transform-request-tools-test
  (testing "canonical tools and auto tool choice are normalized"
    (let [request {:model "glm-5.2"
                   :messages [{:role :user :content "weather"}]
                   :tools [{:type "function"
                            :function {:name "weather"
                                       :description "Get weather"
                                       :parameters {:type "object"}
                                       :strict true}}]
                   :tool-choice :auto}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= "function" (get-in transformed [:tools 0 :type])))
      (is (= "weather" (get-in transformed [:tools 0 :function :name])))
      (is (= true (get-in transformed [:tools 0 :function :strict])))
      (is (= "auto" (:tool_choice transformed)))))
  (testing "unsupported tool choice fails before HTTP"
    (is (thrown-invalid-request?
         #(zai/transform-request-impl :zai
                                      {:model "glm-5.2"
                                       :messages [{:role :user :content "weather"}]
                                       :tool-choice :required}
                                      {})))))

(deftest transform-request-preserves-reasoning-content-test
  (testing "caller-provided assistant reasoning content is preserved outbound"
    (let [request {:model "glm-5.2"
                   :messages [{:role :assistant
                               :content "Prior answer"
                               :reasoning-content "prior hidden reasoning"
                               :tool-calls [{:id "call_1"
                                             :type "function"
                                             :function {:name "lookup"
                                                        :arguments "{}"}}]}]}
          transformed (zai/transform-request-impl :zai request {})]
      (is (= "prior hidden reasoning"
             (get-in transformed [:messages 0 :reasoning_content])))
      (is (= "lookup"
             (get-in transformed [:messages 0 :tool_calls 0 :function :name]))))))

(deftest transform-response-test
  (testing "response extracts reasoning content, tool calls, and cached usage"
    (let [response {:body {:id "chatcmpl-1"
                           :object "chat.completion"
                           :created 123
                           :model "glm-5.2"
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
                                   :prompt_tokens_details {:cached_tokens 4}}}}
          transformed (zai/transform-response-impl :zai response)]
      (is (= "Hidden" (get-in transformed [:choices 0 :message :reasoning-content])))
      (is (= "lookup" (get-in transformed [:choices 0 :message :tool-calls 0 :function :name])))
      (is (= :tool_calls (get-in transformed [:choices 0 :finish-reason])))
      (is (= 4 (get-in transformed [:usage :cached-tokens]))))))

(deftest transform-streaming-chunk-test
  (testing "streaming chunk extracts reasoning content and usage cache details"
    (let [chunk {:id "chunk-1"
                 :object "chat.completion.chunk"
                 :created 124
                 :model "glm-5.2"
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
                         :prompt_tokens_details {:cached_tokens 3}}}
          transformed (zai/transform-streaming-chunk-impl :zai chunk)]
      (is (= :assistant (get-in transformed [:choices 0 :delta :role])))
      (is (= "R" (get-in transformed [:choices 0 :delta :reasoning-content])))
      (is (= "{" (get-in transformed [:choices 0 :delta :tool-calls 0 :function :arguments])))
      (is (= 3 (get-in transformed [:usage :cached-tokens]))))))

(deftest cost-and-capability-test
  (testing "known and unknown cost maps"
    (is (= {:input 0.0000014 :output 0.0000044 :cache-read 0.00000026}
           (zai/get-cost-per-token-impl :zai "glm-5.2")))
    (is (= {:input 0.0 :output 0.0}
           (zai/get-cost-per-token-impl :zai "unknown"))))
  (testing "capability helpers"
    (is (true? (zai/supports-streaming-impl :zai)))
    (is (true? (zai/supports-function-calling-impl :zai)))))

(deftest multimethod-registration-test
  (testing "Z.AI provider is registered for chat completion multimethods"
    (is (true? (providers/provider-available? :zai)))
    (is (true? (providers/supports-streaming? :zai)))
    (is (true? (providers/supports-function-calling? :zai)))
    (is (false? (providers/supports-embeddings? :zai)))
    (is (= {:input 0.0000011 :output 0.0000045 :cache-read 0.00000022}
           (providers/get-cost-per-token :zai "glm-4.5-airx")))))
