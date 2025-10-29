(ns litellm.providers.anthropic-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.providers.anthropic :as anthropic]
            [cheshire.core :as json]))

(deftest test-transform-tools
  (testing "Transform tools to Anthropic format"
    (let [tools [{:type "function"
                  :function {:name "get_weather"
                            :description "Get the weather for a location"
                            :parameters {:type "object"
                                       :properties {:location {:type "string"
                                                             :description "The city and state"}
                                                   :unit {:type "string"
                                                         :enum ["celsius" "fahrenheit"]}}
                                       :required ["location"]}}}]
          result (anthropic/transform-tools tools)]
      (is (= 1 (count result)))
      (is (= "get_weather" (:name (first result))))
      (is (= "Get the weather for a location" (:description (first result))))
      (is (= {:type "object"
              :properties {:location {:type "string"
                                     :description "The city and state"}
                          :unit {:type "string"
                                :enum ["celsius" "fahrenheit"]}}
              :required ["location"]}
             (:input_schema (first result)))))))

(deftest test-transform-tool-choice
  (testing "Transform tool choice auto"
    (is (= {:type "auto"} (anthropic/transform-tool-choice :auto))))
  
  (testing "Transform tool choice any"
    (is (= {:type "any"} (anthropic/transform-tool-choice :any))))
  
  (testing "Transform tool choice none"
    (is (= {:type "none"} (anthropic/transform-tool-choice :none))))
  
  (testing "Transform tool choice specific tool"
    (is (= {:type "tool" :name "get_weather"} 
           (anthropic/transform-tool-choice {:name "get_weather"})))))

(deftest test-transform-messages-with-tool-calls
  (testing "Transform messages with tool calls"
    (let [messages [{:role :user :content "What's the weather in SF?"}
                    {:role :assistant 
                     :content "Let me check that for you."
                     :tool-calls [{:id "call_123"
                                  :type "function"
                                  :function {:name "get_weather"
                                            :arguments (json/encode {:location "San Francisco, CA" :unit "fahrenheit"})}}]}
                    {:role :tool
                     :tool-call-id "call_123"
                     :content "72°F and sunny"}]
          result (anthropic/transform-messages messages)]
      
      (is (= 3 (count (:messages result))))
      
      ;; Check user message
      (is (= "user" (-> result :messages first :role)))
      (is (= "What's the weather in SF?" (-> result :messages first :content)))
      
      ;; Check assistant message with tool call
      (is (= "assistant" (-> result :messages second :role)))
      (let [content (-> result :messages second :content)]
        (is (seq content))
        (is (= 2 (count content)))
        (is (= "text" (:type (first content))))
        (is (= "Let me check that for you." (:text (first content))))
        (is (= "tool_use" (:type (second content))))
        (is (= "call_123" (:id (second content))))
        (is (= "get_weather" (:name (second content))))
        (is (= {:location "San Francisco, CA" :unit "fahrenheit"} (:input (second content)))))
      
      ;; Check tool result message
      (is (= "user" (->> result :messages (drop 2) first :role)))
      (let [content (->> result :messages (drop 2) first :content)]
        (is (seq content))
        (is (= "tool_result" (:type (first content))))
        (is (= "call_123" (:tool_use_id (first content))))
        (is (= "72°F and sunny" (:content (first content))))))))

(deftest test-transform-tool-calls-response
  (testing "Transform Anthropic tool uses to standard format"
    (let [content [{:type "text" :text "Let me check that."}
                   {:type "tool_use"
                    :id "toolu_123"
                    :name "get_weather"
                    :input {:location "San Francisco, CA" :unit "fahrenheit"}}]
          result (anthropic/transform-tool-calls content)]
      
      (is (= 1 (count result)))
      (is (= "toolu_123" (:id (first result))))
      (is (= "function" (:type (first result))))
      (is (= "get_weather" (get-in result [0 :function :name])))
      (is (= "{\"location\":\"San Francisco, CA\",\"unit\":\"fahrenheit\"}"
             (get-in result [0 :function :arguments]))))))

(deftest test-transform-choice-with-tool-calls
  (testing "Transform Anthropic response with tool calls"
    (let [response {:content [{:type "text" :text "Let me check that."}
                             {:type "tool_use"
                              :id "toolu_123"
                              :name "get_weather"
                              :input {:location "San Francisco, CA" :unit "fahrenheit"}}]
                   :stop_reason "tool_use"}
          result (anthropic/transform-choice response 0)]
      
      (is (= 0 (:index result)))
      (is (= :assistant (get-in result [:message :role])))
      (is (= "Let me check that." (get-in result [:message :content])))
      (is (= 1 (count (get-in result [:message :tool-calls]))))
      (is (= "toolu_123" (get-in result [:message :tool-calls 0 :id])))
      (is (= "function" (get-in result [:message :tool-calls 0 :type])))
      (is (= "get_weather" (get-in result [:message :tool-calls 0 :function :name])))
      (is (= :tool_use (:finish-reason result))))))

(deftest test-transform-request-with-tools
  (testing "Transform request with tools"
    (let [request {:model "claude-3-sonnet"
                  :messages [{:role :user :content "What's the weather?"}]
                  :tools [{:type "function"
                          :function {:name "get_weather"
                                    :description "Get weather"
                                    :parameters {:type "object"
                                               :properties {:location {:type "string"}}}}}]
                  :tool-choice :auto
                  :max-tokens 1024}
          config {:api-key "test-key"}
          result (anthropic/transform-request-impl :anthropic request config)]
      
      (is (= "claude-3-sonnet-20240229" (:model result)))
      (is (= 1024 (:max_tokens result)))
      (is (seq? (:tools result)))
      (is (= 1 (count (:tools result))))
      (is (= "get_weather" (-> result :tools first :name)))
      (is (= {:type "auto"} (:tool_choice result))))))

(deftest test-supports-function-calling
  (testing "Anthropic supports function calling"
    (is (true? (anthropic/supports-function-calling-impl :anthropic)))))

(deftest test-reasoning-effort-transformation
  (testing "Transform reasoning-effort :low"
    (is (= {:type "enabled" :budget_tokens 1024}
           (anthropic/reasoning-effort->thinking-config :low))))
  
  (testing "Transform reasoning-effort :medium"
    (is (= {:type "enabled" :budget_tokens 4096}
           (anthropic/reasoning-effort->thinking-config :medium))))
  
  (testing "Transform reasoning-effort :high"
    (is (= {:type "enabled" :budget_tokens 10000}
           (anthropic/reasoning-effort->thinking-config :high))))
  
  (testing "Transform nil reasoning-effort"
    (is (nil? (anthropic/reasoning-effort->thinking-config nil)))))

(deftest test-thinking-config-transformation
  (testing "Transform thinking config"
    (is (= {:type "enabled" :budget_tokens 2048}
           (anthropic/transform-thinking-config {:type :enabled :budget-tokens 2048}))))
  
  (testing "Transform nil thinking config"
    (is (nil? (anthropic/transform-thinking-config nil)))))

(deftest test-transform-request-with-reasoning-effort
  (testing "Transform request with reasoning-effort"
    (let [request {:model "claude-3-7-sonnet-20250219"
                  :messages [{:role :user :content "What is 2+2?"}]
                  :reasoning-effort :low
                  :max-tokens 1024}
          config {:api-key "test-key"}
          result (anthropic/transform-request-impl :anthropic request config)]
      
      (is (= "claude-3-7-sonnet-20250219" (:model result)))
      (is (= {:type "enabled" :budget_tokens 1024} (:thinking result))))))

(deftest test-transform-request-with-thinking-config
  (testing "Transform request with explicit thinking config"
    (let [request {:model "claude-3-7-sonnet-20250219"
                  :messages [{:role :user :content "Explain quantum physics"}]
                  :thinking {:type :enabled :budget-tokens 5000}
                  :max-tokens 1024}
          config {:api-key "test-key"}
          result (anthropic/transform-request-impl :anthropic request config)]
      
      (is (= "claude-3-7-sonnet-20250219" (:model result)))
      (is (= {:type "enabled" :budget_tokens 5000} (:thinking result))))))

(deftest test-extract-reasoning-content
  (testing "Extract reasoning content from response"
    (let [content [{:type "thinking" :thinking "Let me think about this step by step..."}
                   {:type "text" :text "The answer is 4."}]
          result (anthropic/extract-reasoning-content content)]
      
      (is (= "Let me think about this step by step..." result))))
  
  (testing "Extract multiple thinking blocks"
    (let [content [{:type "thinking" :thinking "First, I'll consider..."}
                   {:type "text" :text "Some text"}
                   {:type "thinking" :thinking "Then, I'll analyze..."}]
          result (anthropic/extract-reasoning-content content)]
      
      (is (= "First, I'll consider...\nThen, I'll analyze..." result))))
  
  (testing "No reasoning content"
    (let [content [{:type "text" :text "Just text"}]
          result (anthropic/extract-reasoning-content content)]
      
      (is (nil? result)))))

(deftest test-extract-thinking-blocks
  (testing "Extract thinking blocks from response"
    (let [content [{:type "thinking" 
                    :thinking "Step 1: Analyze the problem"
                    :signature "sig123"}
                   {:type "text" :text "The answer is..."}]
          result (anthropic/extract-thinking-blocks content)]
      
      (is (= 1 (count result)))
      (is (= "thinking" (:type (first result))))
      (is (= "Step 1: Analyze the problem" (:thinking (first result))))
      (is (= "sig123" (:signature (first result))))))
  
  (testing "No thinking blocks"
    (let [content [{:type "text" :text "Just text"}]
          result (anthropic/extract-thinking-blocks content)]
      
      (is (nil? result)))))

(deftest test-transform-choice-with-reasoning
  (testing "Transform response with reasoning content"
    (let [response {:content [{:type "thinking" 
                              :thinking "Let me solve this step by step..."
                              :signature "sig456"}
                             {:type "text" :text "The answer is 4."}]
                   :stop_reason "end_turn"}
          result (anthropic/transform-choice response 0)]
      
      (is (= :assistant (get-in result [:message :role])))
      (is (= "The answer is 4." (get-in result [:message :content])))
      (is (= "Let me solve this step by step..." (get-in result [:message :reasoning-content])))
      (is (= 1 (count (get-in result [:message :thinking-blocks]))))
      (is (= "thinking" (get-in result [:message :thinking-blocks 0 :type]))))))

(deftest test-transform-streaming-chunk-with-tool-use
  (testing "Transform streaming chunk with tool use start"
    (let [chunk {:type "content_block_start"
                :message_id "msg_123"
                :content_block {:type "tool_use"
                              :id "toolu_456"
                              :name "get_weather"}}
          result (anthropic/transform-streaming-chunk-impl :anthropic chunk)]
      
      (is (= "msg_123" (:id result)))
      (is (= "chat.completion.chunk" (:object result)))
      (is (= :assistant (get-in result [:choices 0 :delta :role])))
      (is (= "toolu_456" (get-in result [:choices 0 :delta :tool-calls 0 :id])))
      (is (= "function" (get-in result [:choices 0 :delta :tool-calls 0 :type])))
      (is (= "get_weather" (get-in result [:choices 0 :delta :tool-calls 0 :function :name])))))
  
  (testing "Transform streaming chunk with tool input delta"
    (let [chunk {:type "content_block_delta"
                :message_id "msg_123"
                :delta {:partial_json "{\"location\":"}}
          result (anthropic/transform-streaming-chunk-impl :anthropic chunk)]
      
      (is (= "msg_123" (:id result)))
      (is (= "{\"location\":" (get-in result [:choices 0 :delta :tool-calls 0 :function :arguments]))))))

(deftest test-transform-streaming-chunk-with-thinking
  (testing "Transform streaming chunk with thinking block start"
    (let [chunk {:type "content_block_start"
                :message_id "msg_789"
                :content_block {:type "thinking"}}
          result (anthropic/transform-streaming-chunk-impl :anthropic chunk)]
      
      (is (= "msg_789" (:id result)))
      (is (= "chat.completion.chunk" (:object result)))
      (is (= :assistant (get-in result [:choices 0 :delta :role])))
      (is (= 1 (count (get-in result [:choices 0 :delta :thinking-blocks]))))
      (is (= "thinking" (get-in result [:choices 0 :delta :thinking-blocks 0 :type])))))
  
  (testing "Transform streaming chunk with thinking delta"
    (let [chunk {:type "content_block_delta"
                :message_id "msg_789"
                :delta {:thinking "Let me analyze this..."}}
          result (anthropic/transform-streaming-chunk-impl :anthropic chunk)]
      
      (is (= "msg_789" (:id result)))
      (is (= "Let me analyze this..." (get-in result [:choices 0 :delta :reasoning-content]))))))
