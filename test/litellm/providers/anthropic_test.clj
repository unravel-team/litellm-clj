(ns litellm.providers.anthropic-test
  (:require [clojure.test :refer :all]
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
