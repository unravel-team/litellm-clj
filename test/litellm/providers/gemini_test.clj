(ns litellm.providers.gemini-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.gemini :as gemini]))

;; ============================================================================
;; Message Transformation Tests
;; ============================================================================

(deftest test-transform-role
  (testing "Transform roles to Gemini format"
    (is (= "user" (gemini/transform-role :user)))
    (is (= "model" (gemini/transform-role :assistant)))
    (is (= "user" (gemini/transform-role :system)))))

(deftest test-transform-messages
  (testing "Transform messages to Gemini format"
    (let [messages [{:role :user :content "Hello"}
                   {:role :assistant :content "Hi there"}]
          transformed (gemini/transform-messages messages)]
      (is (= 2 (count transformed)))
      (is (= "user" (:role (first transformed))))
      (is (= "Hello" (get-in (first transformed) [:parts 0 :text]))))))

(deftest test-extract-system-instruction
  (testing "Extract system instruction from messages"
    (let [messages [{:role :system :content "You are helpful"}
                   {:role :user :content "Hello"}]
          instruction (gemini/extract-system-instruction messages)]
      (is (some? instruction))
      (is (= "You are helpful" (get-in instruction [:parts 0 :text])))))
  
  (testing "No system instruction when not present"
    (let [messages [{:role :user :content "Hello"}]
          instruction (gemini/extract-system-instruction messages)]
      (is (nil? instruction)))))

(deftest test-supports-streaming
  (testing "Gemini supports streaming"
    (is (true? (gemini/supports-streaming-impl :gemini)))))

(deftest test-supports-function-calling
  (testing "Gemini supports function calling"
    (is (true? (gemini/supports-function-calling-impl :gemini)))))

;; ============================================================================
;; Embedding Tests
;; ============================================================================

(deftest test-transform-embedding-request-single-input
  (testing "Transform embedding request with single string input"
    (let [request {:model "text-embedding-004"
                  :input "Hello world"}
          config {}
          transformed (gemini/transform-embedding-request-impl :gemini request config)
          requests (:requests transformed)]
      (is (= 1 (count requests)))
      (is (= "models/text-embedding-004" (get-in requests [0 :model])))
      (is (= "Hello world" (get-in requests [0 :content :parts 0 :text]))))))

(deftest test-transform-embedding-request-array-input
  (testing "Transform embedding request with array input"
    (let [request {:model "text-embedding-004"
                  :input ["Hello" "World"]}
          config {}
          transformed (gemini/transform-embedding-request-impl :gemini request config)
          requests (:requests transformed)]
      (is (= 2 (count requests)))
      (is (= "models/text-embedding-004" (:model (first requests))))
      (is (= "Hello" (get-in (first requests) [:content :parts 0 :text])))
      (is (= "World" (get-in (second requests) [:content :parts 0 :text]))))))

(deftest test-transform-embedding-response
  (testing "Transform embedding response from Gemini format"
    (let [response {:body {:embeddings [{:values [0.1 0.2 0.3]
                                        :model "models/text-embedding-004"}
                                       {:values [0.4 0.5 0.6]
                                        :model "models/text-embedding-004"}]}}
          transformed (gemini/transform-embedding-response-impl :gemini response)]
      (is (= "list" (:object transformed)))
      (is (= "models/text-embedding-004" (:model transformed)))
      (is (= 2 (count (:data transformed))))
      (is (= [0.1 0.2 0.3] (:embedding (first (:data transformed)))))
      (is (= [0.4 0.5 0.6] (:embedding (second (:data transformed)))))
      (is (= 0 (:index (first (:data transformed)))))
      (is (= 1 (:index (second (:data transformed))))))))

(deftest test-supports-embeddings
  (testing "Gemini supports embeddings"
    (is (true? (gemini/supports-embeddings-impl :gemini)))))

(deftest test-embedding-cost-map
  (testing "Embedding cost map contains text-embedding-004 model"
    (is (contains? gemini/default-embedding-cost-map "text-embedding-004"))
    
    (testing "Costs have correct structure"
      (let [cost (get gemini/default-embedding-cost-map "text-embedding-004")]
        (is (contains? cost :input))
        (is (contains? cost :output))
        (is (number? (:input cost)))
        (is (zero? (:output cost)))))))

;; ============================================================================
;; Model Cost Tests
;; ============================================================================

(deftest test-get-cost-per-token
  (testing "Get cost for known model"
    (let [cost (gemini/get-cost-per-token-impl :gemini "gemini-1.5-flash")]
      (is (some? cost))
      (is (contains? cost :input))
      (is (contains? cost :output))))
  
  (testing "Get cost for unknown model returns zeros"
    (let [cost (gemini/get-cost-per-token-impl :gemini "unknown-model")]
      (is (= {:input 0.0 :output 0.0} cost)))))

;; ============================================================================
;; Transform Request Tests
;; ============================================================================

(deftest test-transform-request-basic
  (testing "Transform basic request"
    (let [request {:model "gemini-1.5-flash"
                  :messages [{:role :user :content "Hello"}]
                  :max-tokens 100}
          config {}
          transformed (gemini/transform-request-impl :gemini request config)]
      (is (= "gemini-1.5-flash" (:model transformed)))
      (is (= 100 (get-in transformed [:generation_config :maxOutputTokens])))
      (is (= 1 (count (:contents transformed)))))))

(deftest test-transform-request-with-system
  (testing "Transform request with system message"
    (let [request {:model "gemini-1.5-flash"
                  :messages [{:role :system :content "You are helpful"}
                            {:role :user :content "Hello"}]
                  :max-tokens 100}
          config {}
          transformed (gemini/transform-request-impl :gemini request config)]
      (is (= "gemini-1.5-flash" (:model transformed)))
      (is (some? (:system_instruction transformed)))
      (is (= "You are helpful" (get-in transformed [:system_instruction :parts 0 :text])))
      ;; Should only have user message in contents (system extracted)
      (is (= 1 (count (:contents transformed)))))))

;; ============================================================================
;; Generation Config Tests
;; ============================================================================

(deftest test-transform-generation-config
  (testing "Transform generation config with all parameters"
    (let [request {:temperature 0.8
                  :top-p 0.9
                  :max-tokens 100
                  :stop ["STOP", "END"]}
          config (gemini/transform-generation-config request)]
      (is (= 0.8 (:temperature config)))
      (is (= 0.9 (:topP config)))
      (is (= 100 (:maxOutputTokens config)))
      (is (= ["STOP", "END"] (:stopSequences config)))))
  
  (testing "Transform generation config with single stop string"
    (let [request {:stop "STOP"}
          config (gemini/transform-generation-config request)]
      (is (= ["STOP"] (:stopSequences config))))))

;; ============================================================================
;; Response Transformation Tests
;; ============================================================================

(deftest test-transform-usage
  (testing "Transform usage information"
    (let [usage {:prompt_token_count 10
                :candidates_token_count 20
                :total_token_count 30}
          transformed (gemini/transform-usage usage)]
      (is (= 10 (:prompt-tokens transformed)))
      (is (= 20 (:completion-tokens transformed)))
      (is (= 30 (:total-tokens transformed))))))

(deftest test-transform-candidate
  (testing "Transform candidate to standard format"
    (let [candidate {:content {:parts [{:text "Hello there"}]}
                    :finish_reason "STOP"}
          transformed (gemini/transform-candidate candidate)]
      (is (= 0 (:index transformed)))
      (is (= :assistant (get-in transformed [:message :role])))
      (is (= "Hello there" (get-in transformed [:message :content])))
      (is (= :stop (:finish-reason transformed))))))
