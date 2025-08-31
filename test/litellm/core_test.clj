(ns litellm.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.core :as litellm]
            [litellm.specs :as specs]
            [clojure.spec.alpha :as s]))

(deftest test-system-lifecycle
  (testing "System can be started and stopped"
    (let [config {:telemetry {:enabled false}  ; Disable telemetry for tests
                  :thread-pools {:io-pool-size 2
                                 :cpu-pool-size 2}}
          system (litellm/start-system config)]
      
      (is (some? system) "System should be created")
      (is (contains? system :thread-pools) "System should have thread pools")
      (is (contains? system :telemetry) "System should have telemetry")
      (is (contains? system :providers) "System should have providers")
      
      ;; Stop the system
      (litellm/stop-system system)
      (is true "System should stop without errors"))))

(deftest test-request-validation
  (testing "Request validation works correctly"
    (let [valid-request {:model "gpt-3.5-turbo"
                         :messages [{:role "user" :content "Hello"}]}
          invalid-request {:model "gpt-3.5-turbo"}]  ; Missing messages
      
      (is (s/valid? ::specs/completion-request valid-request)
          "Valid request should pass validation")
      
      (is (not (s/valid? ::specs/completion-request invalid-request))
          "Invalid request should fail validation"))))

(deftest test-provider-registry
  (testing "Provider registry works"
    (let [system (litellm/start-system {:telemetry {:enabled false}
                                        :thread-pools {:io-pool-size 2
                                                       :cpu-pool-size 2}})]
      
      (try
        (is (contains? (:providers system) "openai")
            "OpenAI provider should be registered")
        
        (finally
          (litellm/stop-system system))))))

(deftest test-model-to-provider-mapping
  (testing "Model to provider mapping works"
    (is (= "openai" (litellm/model->provider "gpt-3.5-turbo")))
    (is (= "openai" (litellm/model->provider "gpt-4")))
    (is (= "openai" (litellm/model->provider "text-davinci-003")))
    
    ;; Test unknown model
    (is (= "openai" (litellm/model->provider "unknown-model"))
        "Unknown models should default to openai")))

(deftest test-error-handling
  (testing "Error handling works correctly"
    (let [system (litellm/start-system {:telemetry {:enabled false}
                                        :thread-pools {:io-pool-size 2
                                                       :cpu-pool-size 2}})]
      
      (try
        ;; Test with invalid request (should be caught by validation)
        (let [invalid-request {:model "gpt-3.5-turbo"}]  ; Missing messages
          (is (thrown? Exception
                       @(litellm/completion system invalid-request))
              "Invalid request should throw exception"))
        
        (finally
          (litellm/stop-system system))))))

;; Integration test (requires API key)
(deftest ^:integration test-actual-api-call
  (testing "Actual API call works (requires OPENAI_API_KEY)"
    (when (System/getenv "OPENAI_API_KEY")
      (let [system (litellm/start-system {:telemetry {:enabled false}
                                          :thread-pools {:io-pool-size 2
                                                         :cpu-pool-size 2}})
            request {:model "gpt-3.5-turbo"
                     :messages [{:role "user" :content "Say 'test successful'"}]
                     :max_tokens 10}]
        
        (try
          (let [response @(litellm/completion system request)]
            (is (map? response) "Response should be a map")
            (is (contains? response :choices) "Response should have choices")
            (is (seq (:choices response)) "Choices should not be empty"))
          
          (finally
            (litellm/stop-system system)))))))
