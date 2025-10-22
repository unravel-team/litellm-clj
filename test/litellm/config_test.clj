(ns litellm.config-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [litellm.config :as config]))

;; Test fixture to clear registry before each test
(defn clear-registry-fixture [f]
  (config/clear-registry!)
  (f)
  (config/clear-registry!))

(use-fixtures :each clear-registry-fixture)

;; ============================================================================
;; Registration Tests
;; ============================================================================

(deftest test-register-simple-config
  (testing "Register simple provider configuration"
    (let [config-name :test-openai
          config-map {:provider :openai
                     :model "gpt-4"
                     :config {:api-key "test-key"}}]
      (is (= config-name (config/register! config-name config-map)))
      (is (= config-map (config/get-config config-name))))))

(deftest test-register-with-router
  (testing "Register configuration with router function"
    (let [router-fn (fn [req] 
                     (if (> (count (:messages req)) 5)
                       {:provider :anthropic :model "claude-3-opus"}
                       {:provider :openai :model "gpt-4o-mini"}))
          config-map {:router router-fn
                     :configs {:openai {:api-key "openai-key"}
                              :anthropic {:api-key "anthropic-key"}}}]
      (config/register! :smart config-map)
      (is (some? (config/get-config :smart)))
      (is (fn? (:router (config/get-config :smart)))))))

(deftest test-register-requires-keyword
  (testing "Register fails with non-keyword config name"
    (is (thrown? Exception
                 (config/register! "string-name" {:provider :openai :model "gpt-4"})))))

(deftest test-unregister
  (testing "Unregister removes configuration"
    (config/register! :temp {:provider :openai :model "gpt-4"})
    (is (some? (config/get-config :temp)))
    (config/unregister! :temp)
    (is (nil? (config/get-config :temp)))))

(deftest test-list-configs
  (testing "List all registered configurations"
    (config/register! :config1 {:provider :openai :model "gpt-4"})
    (config/register! :config2 {:provider :anthropic :model "claude-3-sonnet"})
    (let [configs (config/list-configs)]
      (is (= 2 (count configs)))
      (is (contains? (set configs) :config1))
      (is (contains? (set configs) :config2)))))

(deftest test-clear-registry
  (testing "Clear all configurations"
    (config/register! :config1 {:provider :openai :model "gpt-4"})
    (config/register! :config2 {:provider :anthropic :model "claude-3-sonnet"})
    (is (= 2 (count (config/list-configs))))
    (config/clear-registry!)
    (is (= 0 (count (config/list-configs))))))

;; ============================================================================
;; Resolution Tests
;; ============================================================================

(deftest test-resolve-simple-config
  (testing "Resolve simple static configuration"
    (let [config-map {:provider :openai
                     :model "gpt-4"
                     :config {:api-key "test-key"}}]
      (config/register! :simple config-map)
      (let [resolved (config/resolve-config :simple {:messages []})]
        (is (= :openai (:provider resolved)))
        (is (= "gpt-4" (:model resolved)))
        (is (= {:api-key "test-key"} (:config resolved)))))))

(deftest test-resolve-with-router
  (testing "Resolve configuration with router function"
    (let [router-fn (fn [req] 
                     (if (> (count (:messages req)) 5)
                       {:provider :anthropic :model "claude-3-opus"}
                       {:provider :openai :model "gpt-4o-mini"}))
          config-map {:router router-fn
                     :configs {:openai {:api-key "openai-key"}
                              :anthropic {:api-key "anthropic-key"}}}]
      (config/register! :smart config-map)
      
      ;; Small request -> OpenAI
      (let [resolved (config/resolve-config :smart {:messages [{:role :user :content "hi"}]})]
        (is (= :openai (:provider resolved)))
        (is (= "gpt-4o-mini" (:model resolved)))
        (is (= {:api-key "openai-key"} (:config resolved))))
      
      ;; Large request -> Anthropic
      (let [resolved (config/resolve-config :smart {:messages (repeat 10 {:role :user :content "test"})})]
        (is (= :anthropic (:provider resolved)))
        (is (= "claude-3-opus" (:model resolved)))
        (is (= {:api-key "anthropic-key"} (:config resolved)))))))

(deftest test-resolve-config-not-found
  (testing "Resolve throws exception for unknown config"
    (is (thrown-with-msg? Exception #"Configuration not found"
                          (config/resolve-config :nonexistent {:messages []})))))

;; ============================================================================
;; Validation Tests
;; ============================================================================

(deftest test-validate-simple-config
  (testing "Validate simple provider configuration"
    (is (true? (config/validate-config {:provider :openai :model "gpt-4"})))))

(deftest test-validate-router-config
  (testing "Validate router configuration"
    (is (true? (config/validate-config {:router (fn [req] {:provider :openai :model "gpt-4"})})))))

(deftest test-validate-missing-provider-and-router
  (testing "Validation fails when both provider and router are missing"
    (is (thrown-with-msg? Exception #"must have :provider or :router"
                          (config/validate-config {:model "gpt-4"})))))

(deftest test-validate-provider-without-model
  (testing "Validation fails when provider present but model missing"
    (is (thrown-with-msg? Exception #"must have :model"
                          (config/validate-config {:provider :openai})))))

(deftest test-validate-router-not-function
  (testing "Validation fails when router is not a function"
    (is (thrown-with-msg? Exception #":router must be a function"
                          (config/validate-config {:router "not-a-function"})))))

(deftest test-register-with-validation
  (testing "Register with validation accepts valid config"
    (let [config-map {:provider :openai :model "gpt-4"}]
      (is (= :validated (config/register-with-validation! :validated config-map)))
      (is (some? (config/get-config :validated)))))
  
  (testing "Register with validation rejects invalid config"
    (is (thrown? Exception
                 (config/register-with-validation! :invalid {:model "gpt-4"})))))
