(ns litellm.providers.ollama-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.ollama :as ollama]
            [litellm.providers.core :as core]
            [clojure.spec.alpha :as s]))

(deftest test-provider-creation
  (testing "Ollama provider can be created"
    (let [config {:api-base "http://localhost:11434"}
          provider (ollama/create-ollama-provider config)]
      
      (is (instance? litellm.providers.ollama.OllamaProvider provider)
          "Should create an OllamaProvider instance")
      
      (is (= "ollama" (core/provider-name provider))
          "Provider name should be 'ollama'")
      
      (is (= "http://localhost:11434" (:api-base provider))
          "API base should be set correctly"))))

(deftest test-transform-request
  (testing "Transform request for generate endpoint"
    (let [provider (ollama/create-ollama-provider {})
          request {:model "ollama/llama2"
                   :messages [{:role :user :content "Hello"}]
                   :max-tokens 100
                   :temperature 0.7
                   :top-p 0.9}
          transformed (core/transform-request provider request)]
      
      (is (= "llama2" (:model transformed))
          "Model name should be extracted correctly")
      
      (is (string? (:prompt transformed))
          "Prompt should be a string for generate endpoint")
      
      (is (= false (:stream transformed))
          "Stream should be false by default")
      
      (is (= 100 (get-in transformed [:options :num_predict]))
          "max-tokens should be transformed to num_predict")
      
      (is (= 0.7 (get-in transformed [:options :temperature]))
          "Temperature should be passed through")))
  
  (testing "Transform request for chat endpoint"
    (let [provider (ollama/create-ollama-provider {})
          request {:model "ollama_chat/llama2"
                   :messages [{:role :user :content "Hello"}]
                   :stream true}
          _ (println "Request model:" (:model request))
          _ (println "Is chat check:" (clojure.string/starts-with? (litellm.providers.core/extract-model-name (:model request)) "ollama_chat/"))
          transformed (core/transform-request provider request)
          _ (println "Transformed request:" transformed)]
      
      (is (= "llama2" (:model transformed))
          "Model name should be extracted correctly")
      
      (is (vector? (:messages transformed))
          "Messages should be a vector for chat endpoint")
      
      (is (= true (:stream transformed))
          "Stream should be passed through"))))

(deftest test-supports-capabilities
  (testing "Ollama provider capabilities"
    (let [provider (ollama/create-ollama-provider {})]
      
      (is (true? (core/supports-streaming? provider))
          "Ollama should support streaming")
      
      (is (false? (core/supports-function-calling? provider))
          "Ollama should not support function calling yet"))))

(deftest test-transform-messages
  (testing "Transform messages for chat"
    (let [messages [{:role :user :content "Hello"}
                    {:role :assistant :content "Hi there"}
                    {:role :user :content "How are you?"}]
          transformed (ollama/transform-messages-for-chat messages)]
      
      (is (= 3 (count transformed))
          "Should have same number of messages")
      
      (is (= "user" (get-in transformed [0 :role]))
          "Role should be converted to string")
      
      (is (= "Hello" (get-in transformed [0 :content]))
          "Content should be preserved")))
  
  (testing "Transform messages for generate"
    (let [messages [{:role :user :content "Hello"}
                    {:role :assistant :content "Hi there"}
                    {:role :user :content "How are you?"}]
          transformed (ollama/transform-messages-for-generate messages)]
      
      (is (string? transformed)
          "Should be converted to a string")
      
      (is (re-find #"USER: Hello" transformed)
          "Should contain user message with role prefix")
      
      (is (re-find #"ASSISTANT: Hi there" transformed)
          "Should contain assistant message with role prefix"))))

(deftest test-transform-response
  (testing "Transform generate response"
    (let [provider (ollama/create-ollama-provider {})
          mock-response {:status 200
                         :body {:model "llama2"
                                :response "This is a test response"
                                :prompt_eval_count 10
                                :eval_count 20}
                         :ollama-request-type :generate}
          transformed (core/transform-response provider mock-response)]
      
      (is (= "chat.completion" (:object transformed))
          "Object should be chat.completion")
      
      (is (= "llama2" (:model transformed))
          "Model should be preserved")
      
      (is (= 1 (count (:choices transformed)))
          "Should have one choice")
      
      (is (= :assistant (get-in transformed [:choices 0 :message :role]))
          "Role should be assistant")
      
      (is (= "This is a test response" (get-in transformed [:choices 0 :message :content]))
          "Content should be from response field")
      
      (is (= 10 (get-in transformed [:usage :prompt-tokens]))
          "Prompt tokens should be set")
      
      (is (= 20 (get-in transformed [:usage :completion-tokens]))
          "Completion tokens should be set")
      
      (is (= 30 (get-in transformed [:usage :total-tokens]))
          "Total tokens should be sum of prompt and completion tokens")))
  
  (testing "Transform chat response"
    (let [provider (ollama/create-ollama-provider {})
          mock-response {:status 200
                         :body {:model "llama2"
                                :message {:role "assistant"
                                         :content "This is a test response"}
                                :prompt_eval_count 10
                                :eval_count 20}
                         :ollama-request-type :chat}
          transformed (core/transform-response provider mock-response)]
      
      (is (= "chat.completion" (:object transformed))
          "Object should be chat.completion")
      
      (is (= "llama2" (:model transformed))
          "Model should be preserved")
      
      (is (= 1 (count (:choices transformed)))
          "Should have one choice")
      
      (is (= :assistant (get-in transformed [:choices 0 :message :role]))
          "Role should be assistant")
      
      (is (= "This is a test response" (get-in transformed [:choices 0 :message :content]))
          "Content should be from message content field")
      
      (is (= 10 (get-in transformed [:usage :prompt-tokens]))
          "Prompt tokens should be set")
      
      (is (= 20 (get-in transformed [:usage :completion-tokens]))
          "Completion tokens should be set")
      
      (is (= 30 (get-in transformed [:usage :total-tokens]))
          "Total tokens should be sum of prompt and completion tokens"))))

;; Integration test (requires Ollama server)
(deftest ^:integration test-ollama-connection
  (testing "Ollama connection test (requires local Ollama server)"
    (when (System/getenv "OLLAMA_TEST_ENABLED")
      (let [provider (ollama/create-ollama-provider {:api-base "http://localhost:11434"})
            thread-pools {:api-calls (java.util.concurrent.Executors/newFixedThreadPool 2)
                          :health-checks (java.util.concurrent.Executors/newFixedThreadPool 2)}
            telemetry {:enabled false}
            result (ollama/test-ollama-connection provider thread-pools telemetry)]
        
        (is (:success result)
            "Connection test should succeed")
        
        (is (= "ollama" (:provider result))
            "Provider should be ollama")))))
