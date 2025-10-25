(ns litellm.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.core :as core]
            [examples.system :as system]
            [litellm.schemas]))

(deftest test-system-lifecycle
  (testing "System can be started and stopped"
    (let [config {:providers {"openai" {:provider :openai
                                        :api-key "test-key"}}
                  :thread-pools-config {:api-calls {:pool-size 2}}}
          sys (system/create-system config)]
      
      (is (some? sys) "System should be created")
      (is (contains? sys :thread-pools) "System should have thread pools")
      (is (contains? sys :config) "System should have config")
      (is (contains? sys :providers) "System should have providers")
      
      ;; Stop the system
      (system/shutdown-system! sys)
      (is true "System should stop without errors"))))

(deftest test-request-validation
  (testing "Request validation works correctly"
    (let [valid-request {:model "gpt-3.5-turbo"
                         :messages [{:role :user :content "Hello"}]}
          invalid-request {:model "gpt-3.5-turbo"}]  ; Missing messages
      
      ;; Using schemas instead of specs
      (is (litellm.schemas/valid-request? valid-request)
          "Valid request should pass validation")
      
      (is (not (litellm.schemas/valid-request? invalid-request))
          "Invalid request should fail validation"))))

(deftest test-provider-registry
  (testing "Provider registry works"
    (let [sys (system/create-system {:providers {"openai" {:provider :openai
                                                           :api-key "test-key"}}
                                      :thread-pools-config {:api-calls {:pool-size 2}}})]
      
      (try
        ;; Since providers are explicitly registered in config, check if system has providers map
        (is (map? (:providers sys))
            "Providers should be a map")
        
        (finally
          (system/shutdown-system! sys))))))


(deftest test-provider-discovery
  (testing "Provider discovery works"
    (is (seq (core/list-providers)) "Should have registered providers")
    (is (core/provider-available? :openai) "OpenAI provider should be available")))


;; Integration test for system-independent API (requires API key)
(deftest ^:integration test-system-independent-completion
  (testing "System-independent completion works (requires OPENAI_API_KEY)"
    (when (System/getenv "OPENAI_API_KEY")
      (let [request {:messages [{:role :user :content "Say 'test successful'"}]
                     :max-tokens 10}
            config {:api-key (System/getenv "OPENAI_API_KEY")}
            response (core/completion :openai "gpt-3.5-turbo" request config)]
        (is (map? response) "Response should be a map")
        (is (contains? response :choices) "Response should have choices")
        (is (seq (:choices response)) "Choices should not be empty")))))

;; Integration test for system-based API (requires API key)
(deftest ^:integration test-system-based-completion
  (testing "System-based completion works (requires OPENAI_API_KEY)"
    (when (System/getenv "OPENAI_API_KEY")
      (let [sys (system/create-system {:providers {"openai" {:provider :openai
                                                              :api-key (System/getenv "OPENAI_API_KEY")}}
                                        :thread-pools-config {:api-calls {:pool-size 2}}})
            request {:provider :openai
                     :model "gpt-3.5-turbo"
                     :messages [{:role :user :content "Say 'test successful'"}]
                     :max-tokens 10}]
        
        (try
          (let [response (system/make-request sys request)]
            (is (map? response) "Response should be a map")
            (is (contains? response :choices) "Response should have choices")
            (is (seq (:choices response)) "Choices should not be empty"))
          
          (finally
            (system/shutdown-system! sys)))))))
