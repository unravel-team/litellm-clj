(ns litellm.providers.deepseek-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.errors :as errors]
            [litellm.providers.core :as providers]
            [litellm.providers.deepseek :as deepseek]))

(deftest url-building-test
  (testing "DeepSeek endpoint URLs tolerate trailing slash overrides"
    (is (= "https://api.deepseek.com/chat/completions"
           (deepseek/chat-url {})))
    (is (= "https://example.test/chat/completions"
           (deepseek/chat-url {:api-base "https://example.test/"})))
    (is (= "https://api.deepseek.com/models"
           (deepseek/models-url {})))))

(deftest transform-request-basic-test
  (testing "basic request uses DeepSeek chat fields"
    (let [request {:model "deepseek-v4-pro"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 128}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= "deepseek-v4-pro" (:model transformed)))
      (is (= [{:role "user" :content "Hello"}] (:messages transformed)))
      (is (= 128 (:max_tokens transformed)))
      (is (not (contains? transformed :max_completion_tokens))))))

(deftest transform-request-optional-params-test
  (testing "optional params become OpenAI-compatible snake_case fields"
    (let [request {:model "deepseek-v4-flash"
                   :messages [{:role :user :content "Return JSON"}]
                   :temperature 0.1
                   :top-p 0.9
                   :frequency-penalty 0.2
                   :presence-penalty 0.3
                   :stop ["END"]
                   :stream true
                   :response-format {:type :json-object}
                   :stream-options {:include-usage true}
                   :extra-body {:logprobs true}}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= 0.1 (:temperature transformed)))
      (is (= 0.9 (:top_p transformed)))
      (is (= 0.2 (:frequency_penalty transformed)))
      (is (= 0.3 (:presence_penalty transformed)))
      (is (= ["END"] (:stop transformed)))
      (is (true? (:stream transformed)))
      (is (= {:type "json_object"} (:response_format transformed)))
      (is (= {:include_usage true} (:stream_options transformed)))
      (is (true? (:logprobs transformed)))))
  (testing "extra-body cannot override protected fields"
    (let [error (try
                  (deepseek/transform-request-impl :deepseek
                                                   {:model "deepseek-v4-pro"
                                                    :messages [{:role :user :content "hi"}]
                                                    :extra-body {:model "override"}}
                                                   {})
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    e))]
      (is (some? error))
      (is (errors/error-type? error :litellm/invalid-request)))))

(deftest transform-request-thinking-test
  (testing "reasoning effort enables thinking and maps DeepSeek effort values"
    (let [request {:model "deepseek-v4-pro"
                   :messages [{:role :user :content "Think"}]
                   :reasoning-effort :low}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= {:type "enabled"} (:thinking transformed)))
      (is (= "high" (:reasoning_effort transformed)))))
  (testing "max-compatible effort maps to max"
    (let [request {:model "deepseek-v4-pro"
                   :messages [{:role :user :content "Think hard"}]
                   :reasoning-effort :xhigh}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= "max" (:reasoning_effort transformed)))))
  (testing "minimal/none disables thinking without sending reasoning_effort"
    (let [request {:model "deepseek-v4-pro"
                   :messages [{:role :user :content "No reasoning"}]
                   :reasoning-effort :none}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= {:type "disabled"} (:thinking transformed)))
      (is (not (contains? transformed :reasoning_effort)))))
  (testing "explicit thinking wins over derived thinking"
    (let [request {:model "deepseek-v4-pro"
                   :messages [{:role :user :content "Think"}]
                   :thinking {:type :disabled}
                   :reasoning-effort :high}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= {:type "disabled"} (:thinking transformed)))
      (is (= "high" (:reasoning_effort transformed))))))

(deftest transform-request-tools-test
  (testing "canonical tools and tool choice are normalized"
    (let [request {:model "deepseek-v4-flash"
                   :messages [{:role :user :content "weather"}]
                   :tools [{:type "function"
                            :function {:name "weather"
                                       :description "Get weather"
                                       :parameters {:type "object"}
                                       :strict true}}]
                   :tool-choice :auto}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= "function" (get-in transformed [:tools 0 :type])))
      (is (= "weather" (get-in transformed [:tools 0 :function :name])))
      (is (= true (get-in transformed [:tools 0 :function :strict])))
      (is (= "auto" (:tool_choice transformed)))))
  (testing "legacy tools are normalized"
    (let [request {:model "deepseek-v4-flash"
                   :messages [{:role :user :content "weather"}]
                   :tools [{:tool-type "function"
                            :function {:function-name "weather"
                                       :function-description "Get weather"
                                       :function-parameters {:type "object"}}}]}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= "weather" (get-in transformed [:tools 0 :function :name])))
      (is (= {:type "object"} (get-in transformed [:tools 0 :function :parameters]))))))

(deftest transform-request-preserves-reasoning-content-test
  (testing "caller-provided assistant reasoning content is preserved outbound"
    (let [request {:model "deepseek-v4-pro"
                   :messages [{:role :assistant
                               :content "Prior answer"
                               :reasoning-content "prior hidden reasoning"
                               :tool-calls [{:id "call_1"
                                             :type "function"
                                             :function {:name "lookup"
                                                        :arguments "{}"}}]}]}
          transformed (deepseek/transform-request-impl :deepseek request {})]
      (is (= "prior hidden reasoning"
             (get-in transformed [:messages 0 :reasoning_content])))
      (is (= "lookup"
             (get-in transformed [:messages 0 :tool_calls 0 :function :name]))))))

(deftest transform-response-test
  (testing "response extracts reasoning content, tool calls, and cached usage"
    (let [response {:body {:id "chatcmpl-1"
                           :object "chat.completion"
                           :created 123
                           :model "deepseek-v4-pro"
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
          transformed (deepseek/transform-response-impl :deepseek response)]
      (is (= "Hidden" (get-in transformed [:choices 0 :message :reasoning-content])))
      (is (= "lookup" (get-in transformed [:choices 0 :message :tool-calls 0 :function :name])))
      (is (= :tool_calls (get-in transformed [:choices 0 :finish-reason])))
      (is (= 4 (get-in transformed [:usage :cached-tokens]))))))

(deftest transform-streaming-chunk-test
  (testing "streaming chunk extracts reasoning content and tool-call deltas"
    (let [chunk {:id "chunk-1"
                 :object "chat.completion.chunk"
                 :created 124
                 :model "deepseek-v4-flash"
                 :choices [{:index 0
                            :delta {:role "assistant"
                                    :content "A"
                                    :reasoning_content "R"
                                    :tool_calls [{:index 0
                                                  :id "call_1"
                                                  :type "function"
                                                  :function {:name "lookup"
                                                             :arguments "{"}}]}
                            :finish_reason nil}]}
          transformed (deepseek/transform-streaming-chunk-impl :deepseek chunk)]
      (is (= :assistant (get-in transformed [:choices 0 :delta :role])))
      (is (= "R" (get-in transformed [:choices 0 :delta :reasoning-content])))
      (is (= "{" (get-in transformed [:choices 0 :delta :tool-calls 0 :function :arguments]))))))

(deftest cost-and-capability-test
  (testing "known and unknown cost maps"
    (is (= {:input 0.00000014 :output 0.00000028 :cache-read 0.0000000028}
           (deepseek/get-cost-per-token-impl :deepseek "deepseek-v4-flash")))
    (is (= {:input 0.0 :output 0.0}
           (deepseek/get-cost-per-token-impl :deepseek "unknown"))))
  (testing "capability helpers"
    (is (true? (deepseek/supports-streaming-impl :deepseek)))
    (is (true? (deepseek/supports-function-calling-impl :deepseek)))))

(deftest multimethod-registration-test
  (testing "DeepSeek provider is registered for chat completion multimethods"
    (is (true? (providers/provider-available? :deepseek)))
    (is (true? (providers/supports-streaming? :deepseek)))
    (is (true? (providers/supports-function-calling? :deepseek)))
    (is (false? (providers/supports-embeddings? :deepseek)))
    (is (= {:input 0.000000435 :output 0.00000087 :cache-read 0.000000003625}
           (providers/get-cost-per-token :deepseek "deepseek-v4-pro")))))
