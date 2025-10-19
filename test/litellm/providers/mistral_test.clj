(ns litellm.providers.mistral-test
  (:require [clojure.test :refer :all]
            [litellm.providers.mistral :as mistral]
            [litellm.providers.core :as core]))

(deftest test-transform-messages
  (testing "Transform messages to Mistral format"
    (let [messages [{:role :user :content "Hello"}
                   {:role :assistant :content "Hi there"}
                   {:role :user :content "How are you?"}]
          result (mistral/transform-messages messages)]
      (is (= 3 (count result)))
      (is (= "user" (:role (first result))))
      (is (= "Hello" (:content (first result))))
      (is (= "assistant" (:role (second result)))))))

(deftest test-add-reasoning-system-prompt
  (testing "Add reasoning prompt to magistral models"
    (let [messages [{:role "user" :content "What is 2+2?"}]
          result (mistral/add-reasoning-system-prompt messages "medium")]
      (is (= 2 (count result)))
      (is (= "system" (:role (first result))))
      (is (re-find #"think step-by-step" (:content (first result))))))
  
  (testing "Prepend reasoning prompt to existing system message"
    (let [messages [{:role "system" :content "You are helpful"}
                   {:role "user" :content "What is 2+2?"}]
          result (mistral/add-reasoning-system-prompt messages "high")]
      (is (= 2 (count result)))
      (is (= "system" (:role (first result))))
      (is (re-find #"think step-by-step" (:content (first result))))
      (is (re-find #"You are helpful" (:content (first result)))))))

(deftest test-supports-reasoning
  (testing "Check if models support reasoning"
    (is (mistral/supports-reasoning? "magistral-small-2506"))
    (is (mistral/supports-reasoning? "magistral-medium-2506"))
    (is (not (mistral/supports-reasoning? "mistral-small-latest")))
    (is (not (mistral/supports-reasoning? "mistral-large-latest")))))

(deftest test-transform-tools
  (testing "Transform tools to Mistral format"
    (let [tools [{:tool-type "function"
                 :function {:name "get_weather"
                           :description "Get the weather"
                           :parameters {:type "object"}}}]
          result (mistral/transform-tools tools)]
      (is (= 1 (count result)))
      (is (= "function" (:type (first result))))
      (is (= "get_weather" (get-in (first result) [:function :name]))))))

(deftest test-transform-tool-choice
  (testing "Transform tool choice from keyword"
    (is (= "auto" (mistral/transform-tool-choice :auto)))
    (is (= "none" (mistral/transform-tool-choice :none))))
  
  (testing "Transform tool choice from map"
    (let [choice {:type "function" :function {:name "get_weather"}}]
      (is (= choice (mistral/transform-tool-choice choice))))))

(deftest test-transform-usage
  (testing "Transform usage to standard format"
    (let [usage {:prompt_tokens 10 :completion_tokens 20 :total_tokens 30}
          result (mistral/transform-usage usage)]
      (is (= 10 (:prompt-tokens result)))
      (is (= 20 (:completion-tokens result)))
      (is (= 30 (:total-tokens result))))))

(deftest test-transform-message
  (testing "Transform message to standard format"
    (let [message {:role "user" :content "Hello"}
          result (mistral/transform-message message)]
      (is (= :user (:role result)))
      (is (= "Hello" (:content result)))))
  
  (testing "Transform message with tool calls"
    (let [message {:role "assistant"
                  :content nil
                  :tool_calls [{:id "call_1"
                               :type "function"
                               :function {:name "get_weather"
                                         :arguments "{\"location\":\"SF\"}"}}]}
          result (mistral/transform-message message)]
      (is (= :assistant (:role result)))
      (is (= 1 (count (:tool-calls result))))
      (is (= "call_1" (:id (first (:tool-calls result))))))))

(deftest test-create-mistral-provider
  (testing "Create Mistral provider with defaults"
    (let [config {:api-key "test-key"}
          provider (mistral/create-mistral-provider config)]
      (is (= "test-key" (:api-key provider)))
      (is (= "https://api.mistral.ai/v1" (:api-base provider)))
      (is (= 30000 (:timeout provider)))
      (is (map? (:cost-map provider)))
      (is (map? (:rate-limits provider)))))
  
  (testing "Create Mistral provider with custom config"
    (let [config {:api-key "test-key"
                 :api-base "https://custom.api.com"
                 :timeout 60000}
          provider (mistral/create-mistral-provider config)]
      (is (= "https://custom.api.com" (:api-base provider)))
      (is (= 60000 (:timeout provider))))))

(deftest test-provider-protocol
  (testing "Provider implements protocol correctly"
    (let [provider (mistral/create-mistral-provider {:api-key "test-key"})]
      (is (= "mistral" (core/provider-name provider)))
      (is (true? (core/supports-streaming? provider)))
      (is (true? (core/supports-function-calling? provider)))
      (is (map? (core/get-rate-limits provider)))
      (is (map? (core/get-cost-per-token provider "mistral-small-latest"))))))

(deftest test-transform-request
  (testing "Transform basic request"
    (let [provider (mistral/create-mistral-provider {:api-key "test-key"})
          request {:model "mistral-small-latest"
                  :messages [{:role :user :content "Hello"}]
                  :max-tokens 100
                  :temperature 0.7}
          result (core/transform-request provider request)]
      (is (= "mistral-small-latest" (:model result)))
      (is (= 1 (count (:messages result))))
      (is (= 100 (:max_tokens result)))
      (is (= 0.7 (:temperature result)))))
  
  (testing "Transform request with reasoning effort"
    (let [provider (mistral/create-mistral-provider {:api-key "test-key"})
          request {:model "magistral-small-2506"
                  :messages [{:role :user :content "Solve this"}]
                  :reasoning-effort "medium"}
          result (core/transform-request provider request)]
      (is (= "magistral-small-2506" (:model result)))
      ;; Should have added system message with reasoning prompt
      (is (= "system" (:role (first (:messages result)))))
      (is (re-find #"think step-by-step" (:content (first (:messages result)))))))
  
  (testing "Transform request with tools"
    (let [provider (mistral/create-mistral-provider {:api-key "test-key"})
          request {:model "mistral-large-latest"
                  :messages [{:role :user :content "What's the weather?"}]
                  :tools [{:tool-type "function"
                          :function {:name "get_weather"
                                    :description "Get weather"
                                    :parameters {:type "object"}}}]
                  :tool-choice :auto}
          result (core/transform-request provider request)]
      (is (= 1 (count (:tools result))))
      (is (= "auto" (:tool_choice result))))))

(deftest test-parse-sse-line
  (testing "Parse valid SSE line"
    (let [line "data: {\"id\":\"123\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
          result (mistral/parse-sse-line line)]
      (is (map? result))
      (is (= "123" (:id result)))))
  
  (testing "Skip [DONE] marker"
    (let [line "data: [DONE]"
          result (mistral/parse-sse-line line)]
      (is (nil? result))))
  
  (testing "Skip non-data lines"
    (let [line ": comment"
          result (mistral/parse-sse-line line)]
      (is (nil? result)))))

(deftest test-create-embedding-request
  (testing "Create embedding request with string input"
    (let [result (mistral/create-embedding-request "Hello world" nil)]
      (is (= ["Hello world"] (:input result)))
      (is (= "mistral-embed" (:model result)))))
  
  (testing "Create embedding request with vector input"
    (let [result (mistral/create-embedding-request ["Hello" "World"] "mistral-embed")]
      (is (= ["Hello" "World"] (:input result)))
      (is (= "mistral-embed" (:model result))))))

(deftest test-cost-map
  (testing "Cost map contains expected models"
    (is (contains? mistral/default-cost-map "mistral-small-latest"))
    (is (contains? mistral/default-cost-map "mistral-large-latest"))
    (is (contains? mistral/default-cost-map "magistral-small-2506"))
    (is (contains? mistral/default-cost-map "mistral-embed"))
    (is (contains? mistral/default-cost-map "codestral-latest")))
  
  (testing "Cost values are valid"
    (let [cost (get mistral/default-cost-map "mistral-small-latest")]
      (is (number? (:input cost)))
      (is (number? (:output cost)))
      (is (pos? (:input cost)))
      (is (pos? (:output cost))))))
