(ns litellm.providers.ollama-test
  (:require [litellm.offline]
            [clojure.test :refer [deftest is testing]]
            [litellm.providers.ollama :as ollama]))

(clojure.test/use-fixtures :each litellm.offline/guard)

;; ============================================================================
;; Tool Transformation Tests
;; ============================================================================

(deftest test-transform-tools
  (testing "Transform tools to Ollama format"
    (let [tools [{:type "function"
                  :function {:name "get_weather"
                             :description "Get the weather for a location"
                             :parameters {:type "object"
                                          :properties {:location {:type "string"}}
                                          :required ["location"]}}}]
          result (ollama/transform-tools tools)]
      (is (= 1 (count result)))
      (is (= "function" (:type (first result))))
      (is (= "get_weather" (get-in result [0 :function :name])))
      (is (= "Get the weather for a location" (get-in result [0 :function :description])))
      (is (= {:type "object"
              :properties {:location {:type "string"}}
              :required ["location"]}
             (get-in result [0 :function :parameters])))))

  (testing "Transform tools with default type"
    (let [tools [{:function {:name "my_tool"
                             :description "A tool"}}]
          result (ollama/transform-tools tools)]
      (is (= "function" (:type (first result))))))

  (testing "Transform nil tools returns nil"
    (is (nil? (ollama/transform-tools nil)))))

(deftest test-transform-tool-choice
  (testing "Transform keyword tool choice"
    (is (= "auto" (ollama/transform-tool-choice :auto)))
    (is (= "none" (ollama/transform-tool-choice :none)))
    (is (= "required" (ollama/transform-tool-choice :required))))

  (testing "Transform map tool choice"
    (let [choice {:type "function" :function {:name "get_weather"}}]
      (is (= choice (ollama/transform-tool-choice choice)))))

  (testing "Transform string tool choice"
    (is (= "auto" (ollama/transform-tool-choice "auto")))))

(deftest test-transform-tool-calls
  (testing "Transform Ollama tool calls to standard format"
    (let [tool-calls [{:id "call_123"
                       :type "function"
                       :function {:name "get_weather"
                                  :arguments "{\"location\":\"San Francisco\"}"}}]
          result (ollama/transform-tool-calls tool-calls)]
      (is (= 1 (count result)))
      (is (= "call_123" (:id (first result))))
      (is (= "function" (:type (first result))))
      (is (= "get_weather" (get-in result [0 :function :name])))
      (is (= "{\"location\":\"San Francisco\"}" (get-in result [0 :function :arguments])))))

  (testing "Transform tool calls with default type"
    (let [tool-calls [{:id "call_456"
                       :function {:name "my_tool"
                                  :arguments "{}"}}]
          result (ollama/transform-tool-calls tool-calls)]
      (is (= "function" (:type (first result))))))

  (testing "Transform nil tool calls returns nil"
    (is (nil? (ollama/transform-tool-calls nil)))))

;; ============================================================================
;; Message Transformation Tests
;; ============================================================================

(deftest test-transform-messages-for-chat
  (testing "Transform basic messages"
    (let [messages [{:role :user :content "Hello"}
                    {:role :assistant :content "Hi there!"}]
          result (ollama/transform-messages-for-chat messages)]
      (is (= 2 (count result)))
      (is (= "user" (:role (first result))))
      (is (= "Hello" (:content (first result))))
      (is (= "assistant" (:role (second result))))
      (is (= "Hi there!" (:content (second result))))))

  (testing "Transform tool response message"
    (let [messages [{:role :tool
                     :content "{\"temperature\": 72}"
                     :tool-call-id "call_123"}]
          result (ollama/transform-messages-for-chat messages)]
      (is (= 1 (count result)))
      (is (= "tool" (:role (first result))))
      (is (= "{\"temperature\": 72}" (:content (first result))))
      (is (= "call_123" (:tool_call_id (first result))))))

  (testing "Transform assistant message with tool calls"
    (let [messages [{:role :assistant
                     :content nil
                     :tool-calls [{:id "call_123"
                                   :type "function"
                                   :function {:name "get_weather"
                                              :arguments "{\"location\":\"NYC\"}"}}]}]
          result (ollama/transform-messages-for-chat messages)]
      (is (= 1 (count result)))
      (is (= "assistant" (:role (first result))))
      (is (= 1 (count (:tool_calls (first result)))))
      (is (= "call_123" (get-in result [0 :tool_calls 0 :id])))
      (is (= "get_weather" (get-in result [0 :tool_calls 0 :function :name]))))))

;; ============================================================================
;; Request Transformation Tests
;; ============================================================================

(deftest test-transform-request-with-tools
  (testing "Request with tools uses chat API"
    (let [request {:model "llama3"
                   :messages [{:role :user :content "What's the weather?"}]
                   :tools [{:type "function"
                            :function {:name "get_weather"
                                       :description "Get weather"
                                       :parameters {:type "object"}}}]}
          result (ollama/transform-request-impl :ollama request {})]
      ;; Should have :messages (chat API) not :prompt (generate API)
      (is (contains? result :messages))
      (is (not (contains? result :prompt)))
      (is (= 1 (count (:tools result))))
      (is (= "get_weather" (get-in result [:tools 0 :function :name])))))

  (testing "Request with tool-choice uses chat API"
    (let [request {:model "llama3"
                   :messages [{:role :user :content "What's the weather?"}]
                   :tool-choice :auto}
          result (ollama/transform-request-impl :ollama request {})]
      (is (contains? result :messages))
      (is (= "auto" (:tool_choice result)))))

  (testing "Request without tools uses generate API by default"
    (let [request {:model "llama3"
                   :messages [{:role :user :content "Hello"}]}
          result (ollama/transform-request-impl :ollama request {})]
      (is (contains? result :prompt))
      (is (not (contains? result :messages)))))

  (testing "Request with ollama_chat prefix uses chat API"
    (let [request {:model "ollama_chat/llama3"
                   :messages [{:role :user :content "Hello"}]}
          result (ollama/transform-request-impl :ollama request {})]
      (is (contains? result :messages))
      (is (= "llama3" (:model result))))))

;; ============================================================================
;; Response Transformation Tests
;; ============================================================================

(deftest test-transform-chat-response
  (testing "Transform response without tool calls"
    (let [response {:body {:message {:role "assistant"
                                     :content "The weather is sunny!"}
                           :model "llama3"
                           :prompt_eval_count 10
                           :eval_count 20}}
          result (ollama/transform-chat-response response)]
      (is (= "chat.completion" (:object result)))
      (is (= "llama3" (:model result)))
      (is (= :assistant (get-in result [:choices 0 :message :role])))
      (is (= "The weather is sunny!" (get-in result [:choices 0 :message :content])))
      (is (= :stop (get-in result [:choices 0 :finish-reason])))
      (is (nil? (get-in result [:choices 0 :message :tool-calls])))))

  (testing "Transform response with tool calls"
    (let [response {:body {:message {:role "assistant"
                                     :content nil
                                     :tool_calls [{:id "call_abc123"
                                                   :type "function"
                                                   :function {:name "get_weather"
                                                              :arguments "{\"location\":\"Boston\"}"}}]}
                           :model "llama3"
                           :prompt_eval_count 10
                           :eval_count 5}}
          result (ollama/transform-chat-response response)]
      (is (= :tool_calls (get-in result [:choices 0 :finish-reason])))
      (is (= 1 (count (get-in result [:choices 0 :message :tool-calls]))))
      (is (= "call_abc123" (get-in result [:choices 0 :message :tool-calls 0 :id])))
      (is (= "get_weather" (get-in result [:choices 0 :message :tool-calls 0 :function :name])))
      (is (= "{\"location\":\"Boston\"}" (get-in result [:choices 0 :message :tool-calls 0 :function :arguments]))))))

;; ============================================================================
;; Capability Tests
;; ============================================================================

(deftest test-supports-function-calling
  (testing "Ollama supports function calling"
    (is (true? (ollama/supports-function-calling-impl :ollama)))))

(deftest test-supports-streaming
  (testing "Ollama supports streaming"
    (is (true? (ollama/supports-streaming-impl :ollama)))))
