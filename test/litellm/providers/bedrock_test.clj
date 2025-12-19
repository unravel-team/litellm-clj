(ns litellm.providers.bedrock-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.providers.bedrock :as bedrock]
            [cheshire.core :as json]))

;; ============================================================================
;; Message Transformation Tests
;; ============================================================================

(deftest test-transform-content
  (testing "Transform string content to Bedrock format"
    (let [result (bedrock/transform-content "Hello")]
      (is (= [{:text "Hello"}] result))))

  (testing "Transform vector content to Bedrock format"
    (let [result (bedrock/transform-content [{:type "text" :text "Hello"}])]
      (is (= [{:text "Hello"}] result))))

  (testing "Transform nil content"
    (let [result (bedrock/transform-content nil)]
      (is (= [{:text ""}] result)))))

(deftest test-transform-messages
  (testing "Transform simple user message"
    (let [messages [{:role :user :content "Hello"}]
          result (bedrock/transform-messages messages)]
      (is (nil? (:system result)))
      (is (= 1 (count (:messages result))))
      (is (= "user" (-> result :messages first :role)))
      (is (= [{:text "Hello"}] (-> result :messages first :content)))))

  (testing "Transform messages with system prompt"
    (let [messages [{:role :system :content "You are helpful"}
                    {:role :user :content "Hello"}]
          result (bedrock/transform-messages messages)]
      (is (= [{:text "You are helpful"}] (:system result)))
      (is (= 1 (count (:messages result))))
      (is (= "user" (-> result :messages first :role)))))

  (testing "Transform conversation with multiple messages"
    (let [messages [{:role :user :content "Hello"}
                    {:role :assistant :content "Hi there!"}
                    {:role :user :content "How are you?"}]
          result (bedrock/transform-messages messages)]
      (is (= 3 (count (:messages result))))
      (is (= "user" (-> result :messages first :role)))
      (is (= "assistant" (-> result :messages second :role)))
      (is (= "user" (-> result :messages (nth 2) :role))))))

(deftest test-transform-messages-with-tool-calls
  (testing "Transform messages with tool calls"
    (let [messages [{:role :user :content "What's the weather?"}
                    {:role :assistant
                     :content "Let me check."
                     :tool-calls [{:id "call_123"
                                   :type "function"
                                   :function {:name "get_weather"
                                              :arguments (json/encode {:location "SF"})}}]}
                    {:role :tool
                     :tool-call-id "call_123"
                     :content "72°F and sunny"}]
          result (bedrock/transform-messages messages)]

      (is (= 3 (count (:messages result))))

      ;; Check user message
      (is (= "user" (-> result :messages first :role)))

      ;; Check assistant message with tool call
      (is (= "assistant" (-> result :messages second :role)))
      (let [content (-> result :messages second :content)]
        (is (= 2 (count content)))
        (is (= {:text "Let me check."} (first content)))
        (is (contains? (second content) :toolUse))
        (is (= "call_123" (get-in content [1 :toolUse :toolUseId])))
        (is (= "get_weather" (get-in content [1 :toolUse :name]))))

      ;; Check tool result message
      (is (= "user" (-> result :messages (nth 2) :role)))
      (let [content (-> result :messages (nth 2) :content first)]
        (is (contains? content :toolResult))
        (is (= "call_123" (get-in content [:toolResult :toolUseId])))))))

;; ============================================================================
;; Tool Transformation Tests
;; ============================================================================

(deftest test-transform-tools
  (testing "Transform tools to Bedrock format"
    (let [tools [{:type "function"
                  :function {:name "get_weather"
                             :description "Get the weather for a location"
                             :parameters {:type "object"
                                          :properties {:location {:type "string"}}
                                          :required ["location"]}}}]
          result (bedrock/transform-tools tools)]
      (is (= 1 (count (:tools result))))
      (let [tool (-> result :tools first :toolSpec)]
        (is (= "get_weather" (:name tool)))
        (is (= "Get the weather for a location" (:description tool)))
        (is (= {:type "object"
                :properties {:location {:type "string"}}
                :required ["location"]}
               (get-in tool [:inputSchema :json]))))))

  (testing "Transform nil tools"
    (is (nil? (bedrock/transform-tools nil))))

  (testing "Transform empty tools"
    (is (nil? (bedrock/transform-tools [])))))

(deftest test-transform-tool-choice
  (testing "Transform tool choice auto"
    (is (= {:auto {}} (bedrock/transform-tool-choice :auto))))

  (testing "Transform tool choice any"
    (is (= {:any {}} (bedrock/transform-tool-choice :any))))

  (testing "Transform tool choice none"
    (is (nil? (bedrock/transform-tool-choice :none))))

  (testing "Transform tool choice specific tool"
    (is (= {:tool {:name "get_weather"}}
           (bedrock/transform-tool-choice {:name "get_weather"})))))

;; ============================================================================
;; Response Transformation Tests
;; ============================================================================

(deftest test-extract-text-content
  (testing "Extract text from content blocks"
    (let [content [{:text "Hello "} {:text "world"}]
          result (bedrock/extract-text-content content)]
      (is (= "Hello world" result))))

  (testing "Extract text with mixed content"
    (let [content [{:text "Response"} {:toolUse {:toolUseId "123"}}]
          result (bedrock/extract-text-content content)]
      (is (= "Response" result))))

  (testing "No text content"
    (let [content [{:toolUse {:toolUseId "123"}}]
          result (bedrock/extract-text-content content)]
      (is (nil? result)))))

(deftest test-transform-tool-uses
  (testing "Transform Bedrock tool uses to standard format"
    (let [content [{:text "Let me check."}
                   {:toolUse {:toolUseId "tool_123"
                              :name "get_weather"
                              :input {:location "San Francisco"}}}]
          result (bedrock/transform-tool-uses content)]
      (is (= 1 (count result)))
      (is (= "tool_123" (:id (first result))))
      (is (= "function" (:type (first result))))
      (is (= "get_weather" (get-in result [0 :function :name])))
      (is (= "{\"location\":\"San Francisco\"}"
             (get-in result [0 :function :arguments])))))

  (testing "No tool uses"
    (let [content [{:text "Just text"}]
          result (bedrock/transform-tool-uses content)]
      (is (nil? result)))))

(deftest test-transform-stop-reason
  (testing "Transform end_turn"
    (is (= :stop (bedrock/transform-stop-reason "end_turn"))))

  (testing "Transform tool_use"
    (is (= :tool_calls (bedrock/transform-stop-reason "tool_use"))))

  (testing "Transform max_tokens"
    (is (= :length (bedrock/transform-stop-reason "max_tokens"))))

  (testing "Transform content_filtered"
    (is (= :content_filter (bedrock/transform-stop-reason "content_filtered"))))

  (testing "Transform unknown"
    (is (= :stop (bedrock/transform-stop-reason "unknown")))))

(deftest test-transform-usage
  (testing "Transform usage information"
    (let [usage {:inputTokens 10
                 :outputTokens 20}
          result (bedrock/transform-usage usage)]
      (is (= 10 (:prompt-tokens result)))
      (is (= 20 (:completion-tokens result)))
      (is (= 30 (:total-tokens result)))))

  (testing "Transform nil usage"
    (is (nil? (bedrock/transform-usage nil)))))

(deftest test-transform-choice
  (testing "Transform Bedrock response to choice format"
    (let [response {:output {:message {:role "assistant"
                                       :content [{:text "Hello!"}]}}
                    :stopReason "end_turn"}
          result (bedrock/transform-choice response 0)]
      (is (= 0 (:index result)))
      (is (= :assistant (get-in result [:message :role])))
      (is (= "Hello!" (get-in result [:message :content])))
      (is (= :stop (:finish-reason result)))))

  (testing "Transform response with tool calls"
    (let [response {:output {:message {:role "assistant"
                                       :content [{:text "Let me check."}
                                                 {:toolUse {:toolUseId "tool_456"
                                                            :name "search"
                                                            :input {:query "test"}}}]}}
                    :stopReason "tool_use"}
          result (bedrock/transform-choice response 0)]
      (is (= :assistant (get-in result [:message :role])))
      (is (= "Let me check." (get-in result [:message :content])))
      (is (= 1 (count (get-in result [:message :tool-calls]))))
      (is (= "tool_456" (get-in result [:message :tool-calls 0 :id])))
      (is (= :tool_calls (:finish-reason result))))))

;; ============================================================================
;; Request Transformation Tests
;; ============================================================================

(deftest test-extract-model-name
  (testing "Extract model from bedrock/ prefix"
    (is (= "anthropic.claude-3-haiku-20240307-v1:0"
           (bedrock/extract-model-name "bedrock/anthropic.claude-3-haiku-20240307-v1:0"))))

  (testing "Keep model without prefix"
    (is (= "anthropic.claude-3-haiku-20240307-v1:0"
           (bedrock/extract-model-name "anthropic.claude-3-haiku-20240307-v1:0"))))

  (testing "Handle model aliases"
    (is (= "claude-3-haiku"
           (bedrock/extract-model-name "claude-3-haiku")))))

(deftest test-transform-request-basic
  (testing "Transform basic request"
    (let [request {:model "anthropic.claude-3-haiku-20240307-v1:0"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 100}
          config {}
          result (bedrock/transform-request-impl :bedrock request config)]
      (is (= "anthropic.claude-3-haiku-20240307-v1:0" (:modelId result)))
      (is (= 1 (count (:messages result))))
      (is (= 100 (get-in result [:inferenceConfig :maxTokens])))))

  (testing "Transform request with model alias"
    (let [request {:model "claude-3-haiku"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 100}
          config {}
          result (bedrock/transform-request-impl :bedrock request config)]
      (is (= "anthropic.claude-3-haiku-20240307-v1:0" (:modelId result)))))

  (testing "Transform request with all inference config"
    (let [request {:model "anthropic.claude-3-haiku-20240307-v1:0"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 100
                   :temperature 0.7
                   :top-p 0.9
                   :stop ["END"]}
          config {}
          result (bedrock/transform-request-impl :bedrock request config)]
      (is (= 100 (get-in result [:inferenceConfig :maxTokens])))
      (is (= 0.7 (get-in result [:inferenceConfig :temperature])))
      (is (= 0.9 (get-in result [:inferenceConfig :topP])))
      (is (= ["END"] (get-in result [:inferenceConfig :stopSequences]))))))

(deftest test-transform-request-with-tools
  (testing "Transform request with tools"
    (let [request {:model "anthropic.claude-3-haiku-20240307-v1:0"
                   :messages [{:role :user :content "What's the weather?"}]
                   :tools [{:type "function"
                            :function {:name "get_weather"
                                       :description "Get weather"
                                       :parameters {:type "object"
                                                    :properties {:location {:type "string"}}}}}]
                   :tool-choice :auto
                   :max-tokens 1024}
          config {}
          result (bedrock/transform-request-impl :bedrock request config)]
      (is (= "anthropic.claude-3-haiku-20240307-v1:0" (:modelId result)))
      (is (contains? result :toolConfig))
      (is (= 1 (count (get-in result [:toolConfig :tools]))))
      (is (= "get_weather" (get-in result [:toolConfig :tools 0 :toolSpec :name]))))))

;; ============================================================================
;; Capability Tests
;; ============================================================================

(deftest test-supports-streaming
  (testing "Bedrock supports streaming"
    (is (true? (bedrock/supports-streaming-impl :bedrock)))))

(deftest test-supports-function-calling
  (testing "Bedrock supports function calling"
    (is (true? (bedrock/supports-function-calling-impl :bedrock)))))

(deftest test-get-rate-limits
  (testing "Get Bedrock rate limits"
    (let [limits (bedrock/get-rate-limits-impl :bedrock)]
      (is (contains? limits :requests-per-minute))
      (is (contains? limits :tokens-per-minute)))))

;; ============================================================================
;; Cost Tests
;; ============================================================================

(deftest test-get-cost-per-token
  (testing "Get cost for Claude model"
    (let [cost (bedrock/get-cost-per-token-impl :bedrock "anthropic.claude-3-haiku-20240307-v1:0")]
      (is (some? cost))
      (is (contains? cost :input))
      (is (contains? cost :output))
      (is (number? (:input cost)))
      (is (number? (:output cost)))))

  (testing "Get cost for Nova model"
    (let [cost (bedrock/get-cost-per-token-impl :bedrock "amazon.nova-pro-v1:0")]
      (is (some? cost))
      (is (contains? cost :input))
      (is (contains? cost :output))))

  (testing "Get cost for model alias"
    (let [cost (bedrock/get-cost-per-token-impl :bedrock "claude-3-haiku")]
      (is (some? cost))
      (is (contains? cost :input))))

  (testing "Get cost for unknown model returns zero"
    (let [cost (bedrock/get-cost-per-token-impl :bedrock "unknown-model")]
      (is (= {:input 0.0 :output 0.0} cost)))))

(deftest test-default-cost-map
  (testing "Cost map contains known models"
    (is (contains? bedrock/default-cost-map "anthropic.claude-3-haiku-20240307-v1:0"))
    (is (contains? bedrock/default-cost-map "amazon.nova-pro-v1:0"))
    (is (contains? bedrock/default-cost-map "meta.llama3-1-70b-instruct-v1:0"))))

(deftest test-default-model-mapping
  (testing "Model mapping contains aliases"
    (is (contains? bedrock/default-model-mapping "claude-3-haiku"))
    (is (contains? bedrock/default-model-mapping "nova-pro"))
    (is (contains? bedrock/default-model-mapping "llama-3-1-70b"))))

;; ============================================================================
;; Streaming Chunk Transformation Tests
;; ============================================================================

(deftest test-transform-streaming-chunk-text
  (testing "Transform text delta chunk"
    (let [chunk {:contentBlockDelta {:delta {:text "Hello"}}}
          result (bedrock/transform-streaming-chunk-impl :bedrock chunk)]
      (is (some? result))
      (is (= "chat.completion.chunk" (:object result)))
      (is (= :assistant (get-in result [:choices 0 :delta :role])))
      (is (= "Hello" (get-in result [:choices 0 :delta :content]))))))

(deftest test-transform-streaming-chunk-tool-start
  (testing "Transform tool use start chunk"
    (let [chunk {:contentBlockStart {:start {:toolUse {:toolUseId "tool_789"
                                                       :name "search"}}}}
          result (bedrock/transform-streaming-chunk-impl :bedrock chunk)]
      (is (some? result))
      (is (= "tool_789" (get-in result [:choices 0 :delta :tool-calls 0 :id])))
      (is (= "function" (get-in result [:choices 0 :delta :tool-calls 0 :type])))
      (is (= "search" (get-in result [:choices 0 :delta :tool-calls 0 :function :name]))))))

(deftest test-transform-streaming-chunk-stop
  (testing "Transform message stop chunk"
    (let [chunk {:messageStop {:stopReason "end_turn"}}
          result (bedrock/transform-streaming-chunk-impl :bedrock chunk)]
      (is (some? result))
      (is (= :stop (get-in result [:choices 0 :finish-reason]))))))

(deftest test-transform-streaming-chunk-metadata
  (testing "Transform metadata chunk returns nil"
    (let [chunk {:metadata {:usage {:inputTokens 10 :outputTokens 5}}}
          result (bedrock/transform-streaming-chunk-impl :bedrock chunk)]
      (is (nil? result)))))
