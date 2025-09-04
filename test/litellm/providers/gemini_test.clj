(ns litellm.providers.gemini-test
  (:require [clojure.test :refer :all]
            [litellm.providers.gemini :as gemini]
            [litellm.providers.core :as core]))

(deftest test-transform-role
  (is (= "user" (gemini/transform-role :user)))
  (is (= "model" (gemini/transform-role :assistant)))
  (is (= "user" (gemini/transform-role :system)))
  (is (= "custom" (gemini/transform-role :custom))))

(deftest test-transform-messages
  (testing "Single message"
    (is (= [{:role "user" :parts [{:text "Hello"}]}]
           (gemini/transform-messages [{:role :user :content "Hello"}]))))
  
  (testing "Multiple messages"
    (is (= [{:role "user" :parts [{:text "Hello"}]}
            {:role "model" :parts [{:text "Hi there"}]}
            {:role "user" :parts [{:text "How are you?"}]}]
           (gemini/transform-messages [{:role :user :content "Hello"}
                                      {:role :assistant :content "Hi there"}
                                      {:role :user :content "How are you?"}]))))
  
  (testing "System message"
    (is (= [{:role "user" :parts [{:text "System prompt"}]}
            {:role "user" :parts [{:text "Hello"}]}]
           (gemini/transform-messages [{:role :system :content "System prompt"}
                                      {:role :user :content "Hello"}])))))

(deftest test-extract-system-instruction
  (testing "With system message"
    (is (= {:parts [{:text "You are a helpful assistant"}]}
           (gemini/extract-system-instruction [{:role :system :content "You are a helpful assistant"}
                                              {:role :user :content "Hello"}]))))
  
  (testing "Without system message"
    (is (nil? (gemini/extract-system-instruction [{:role :user :content "Hello"}])))))

(deftest test-transform-generation-config
  (testing "Empty config"
    (is (= {} (gemini/transform-generation-config {}))))
  
  (testing "With temperature"
    (is (= {:temperature 0.7} (gemini/transform-generation-config {:temperature 0.7}))))
  
  (testing "With max tokens"
    (is (= {:maxOutputTokens 100} (gemini/transform-generation-config {:max-tokens 100}))))
  
  (testing "With stop sequence"
    (is (= {:stopSequences ["\n"]} (gemini/transform-generation-config {:stop "\n"}))))
  
  (testing "With multiple stop sequences"
    (is (= {:stopSequences ["\n" "END"]} (gemini/transform-generation-config {:stop ["\n" "END"]})))))

(deftest test-transform-tools
  (testing "Single tool"
    (is (= {:function_declarations
            [{:name "get_weather"
              :description "Get weather information"
              :parameters {:type "object"
                           :properties {:location {:type "string"}}
                           :required ["location"]}}]}
           (gemini/transform-tools [{:tool-type "function"
                                    :function {:function-name "get_weather"
                                              :function-description "Get weather information"
                                              :function-parameters {:type "object"
                                                                   :properties {:location {:type "string"}}
                                                                   :required ["location"]}}}]))))
  
  (testing "No tools"
    (is (nil? (gemini/transform-tools nil)))))

(deftest test-transform-tool-choice
  (is (= "AUTO" (gemini/transform-tool-choice :auto)))
  (is (= "NONE" (gemini/transform-tool-choice :none)))
  (is (= "ANY" (gemini/transform-tool-choice :any)))
  (is (= {:mode "ANY" :allowed_function_names ["get_weather"]}
         (gemini/transform-tool-choice {:name "get_weather"}))))

(deftest test-transform-tool-calls
  (testing "Single function call"
    (is (= [{:id string?
             :type "function"
             :function {:name "get_weather"
                       :arguments "{\"location\":\"Boston\"}"}}]
           (let [result (gemini/transform-tool-calls [{:name "get_weather"
                                                      :args {:location "Boston"}}])]
             [(update (first result) :id (constantly "test-id"))]))))
  
  (testing "No function calls"
    (is (nil? (gemini/transform-tool-calls nil)))))

(deftest test-transform-candidate
  (testing "Text response"
    (is (= {:index 0
            :message {:role :assistant
                     :content "Hello there"
                     :tool-calls nil}
            :finish-reason :stop}
           (gemini/transform-candidate {:content {:parts [{:text "Hello there"}]}
                                       :finish_reason "STOP"}))))
  
  (testing "Function call response"
    (is (= {:index 0
            :message {:role :assistant
                     :content nil
                     :tool-calls [{:id string?
                                  :type "function"
                                  :function {:name "get_weather"
                                            :arguments "{\"location\":\"Boston\"}"}}]}
            :finish-reason :stop}
           (let [result (gemini/transform-candidate {:content {:parts [{:function_call {:name "get_weather"
                                                                                      :args {:location "Boston"}}}]}
                                                   :finish_reason "STOP"})]
             (update-in result [:message :tool-calls 0] #(update % :id (constantly "test-id")))))))
  
  (testing "Max tokens finish reason"
    (is (= {:index 0
            :message {:role :assistant
                     :content "Hello"
                     :tool-calls nil}
            :finish-reason :length}
           (gemini/transform-candidate {:content {:parts [{:text "Hello"}]}
                                       :finish_reason "MAX_TOKENS"})))))

(deftest test-transform-usage
  (testing "With usage data"
    (is (= {:prompt-tokens 10
            :completion-tokens 5
            :total-tokens 15}
           (gemini/transform-usage {:prompt_token_count 10
                                   :candidates_token_count 5
                                   :total_token_count 15}))))
  
  (testing "Missing usage data"
    (is (= {:prompt-tokens 0
            :completion-tokens 0
            :total-tokens 0}
           (gemini/transform-usage {})))))

(deftest test-transform-response
  (testing "Complete response"
    (let [response {:body {:candidates [{:content {:parts [{:text "Hello there"}}
                                                         {:function_call {:name "get_weather"
                                                                         :args {:location "Boston"}}}]
                                         :finish_reason "STOP"}]
                           :usage_metadata {:prompt_token_count 10
                                           :candidates_token_count 5
                                           :total_token_count 15}
                           :model_version "gemini-1.5-flash-latest"}}
          result (gemini/transform-response response)]
      (is (= "chat.completion" (:object result)))
      (is (= "gemini-1.5-flash-latest" (:model result)))
      (is (= 1 (count (:choices result))))
      (is (= {:prompt-tokens 10
              :completion-tokens 5
              :total-tokens 15}
             (:usage result)))))

(deftest test-provider-creation
  (let [provider (gemini/create-gemini-provider {:api-key "test-key"})]
    (is (= "gemini" (core/provider-name provider)))
    (is (core/supports-streaming? provider))
    (is (core/supports-function-calling? provider))))

(deftest test-cost-mapping
  (let [provider (gemini/create-gemini-provider {:api-key "test-key"})]
    (is (= {:input 0.00000075 :output 0.000003}
           (core/get-cost-per-token provider "gemini-1.5-flash")))
    (is (= {:input 0.00000125 :output 0.000005}
           (core/get-cost-per-token provider "gemini-1.5-pro")))
    (is (= {:input 0.0 :output 0.0}
           (core/get-cost-per-token provider "unknown-model")))))

(deftest test-rate-limits
  (let [provider (gemini/create-gemini-provider {:api-key "test-key"})]
    (is (= {:requests-per-minute 60
            :tokens-per-minute 60000}
           (core/get-rate-limits provider)))))

(deftest test-transform-request
  (let [provider (gemini/create-gemini-provider {:api-key "test-key"})
        request {:model "gemini-1.5-flash"
                :messages [{:role :system :content "You are helpful"}
                          {:role :user :content "Hello"}]
                :temperature 0.7
                :max-tokens 100}]
    (let [transformed (core/transform-request provider request)]
      (is (= "gemini-1.5-flash" (:model transformed)))
      (is (= {:parts [{:text "You are helpful"}]} (:system_instruction transformed)))
      (is (= [{:role "user" :parts [{:text "Hello"}]}] (:contents transformed)))
      (is (= {:temperature 0.7 :maxOutputTokens 100} (:generation_config transformed))))))

(deftest test-model-mapping
  (let [provider (gemini/create-gemini-provider {:api-key "test-key"})]
    (is (= "gemini-1.5-flash-latest" (:model-mapping provider "gemini-1.5-flash")))
    (is (= "gemini-1.5-pro-latest" (:model-mapping provider "gemini-1.5-pro")))))

(deftest test-streaming-chunk
  (let [chunk {:candidates [{:content {:parts [{:text "Hello"}]}
                             :finish_reason "STOP"}]
               :model_version "gemini-1.5-flash-latest"}
        result (gemini/transform-streaming-chunk chunk)]
    (is (= "chat.completion.chunk" (:object result)))
    (is (= "gemini-1.5-flash-latest" (:model result)))
    (is (= 1 (count (:choices result))))
    (is (= :assistant (get-in result [:choices 0 :delta :role])))
    (is (= "Hello" (get-in result [:choices 0 :delta :content])))
    (is (= :stop (get-in result [:choices 0 :finish-reason])))))
