(ns litellm.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.core :as litellm]
            [litellm.specs :as specs]
            [clojure.spec.alpha :as s]))

(deftest test-system-lifecycle
  (testing "System can be started and stopped"
    (let [config {:providers {"openai" {:provider :openai}}
                  :thread-pools {:api-calls {:pool-size 2}}}
          system (litellm/create-system config)]
      
      (is (some? system) "System should be created")
      (is (contains? system :thread-pools) "System should have thread pools")
      (is (contains? system :config) "System should have config")
      (is (contains? system :providers) "System should have providers")
      
      ;; Stop the system
      (litellm/shutdown-system! system)
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
    (let [system (litellm/create-system {:providers {"openai" {:provider :openai}}
                                          :thread-pools {:api-calls {:pool-size 2}}})]
      
      (try
        ;; Since providers are explicitly registered in config, check if system has providers map
        (is (map? (:providers system))
            "Providers should be a map")
        
        (finally
          (litellm/shutdown-system! system))))))

(deftest test-model-to-provider-mapping
  (testing "Model to provider mapping works"
    (is (= "openai" (litellm.providers.core/extract-provider-name "gpt-3.5-turbo")))
    (is (= "openai" (litellm.providers.core/extract-provider-name "gpt-4")))
    (is (= "anthropic" (litellm.providers.core/extract-provider-name "claude-3-opus-20240229")))
    (is (= "openrouter" (litellm.providers.core/extract-provider-name "openai/gpt-4")))))

(deftest test-error-handling
  (testing "Error handling works correctly"
    (let [system (litellm/create-system {:providers {"openai" {:provider :openai}}
                                          :thread-pools {:api-calls {:pool-size 2}}})]
      
      (try
        ;; Test with invalid request (should be caught by validation)
        (let [invalid-request {:model "gpt-3.5-turbo"}]  ; Missing messages
          (is (thrown? Exception
                       (litellm/make-request system invalid-request))
              "Invalid request should throw exception"))
        
        (finally
          (litellm/shutdown-system! system))))))

;; Integration test (requires API key)
(deftest ^:integration test-actual-api-call
  (testing "Actual API call works (requires OPENAI_API_KEY)"
    (when (System/getenv "OPENAI_API_KEY")
      (let [system (litellm/create-system {:providers {"openai" {:provider :openai
                                                                  :api-key (System/getenv "OPENAI_API_KEY")}}
                                            :thread-pools {:api-calls {:pool-size 2}}})
            request {:model "gpt-3.5-turbo"
                     :messages [{:role "user" :content "Say 'test successful'"}]
                     :max_tokens 10}]
        
        (try
          (let [response (litellm/make-request system request)]
            (is (map? response) "Response should be a map")
            (is (contains? response :choices) "Response should have choices")
            (is (seq (:choices response)) "Choices should not be empty"))
          
          (finally
            (litellm/shutdown-system! system)))))))
