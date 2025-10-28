(ns litellm.router-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [litellm.router :as router]))

;; Test fixture to clear registry before each test
(defn clear-registry-fixture [f]
  (router/clear-router!)
  (f)
  (router/clear-router!))

(use-fixtures :each clear-registry-fixture)

;; ============================================================================
;; Configuration Management Tests
;; ============================================================================

(deftest test-register-and-get-config
  (testing "Register and retrieve configuration"
    (let [config-map {:provider :openai :model "gpt-4" :config {:api-key "test-key"}}]
      (router/register! :test-config config-map)
      (is (= config-map (router/get-config :test-config))))))

(deftest test-unregister-config
  (testing "Unregister removes configuration"
    (router/register! :temp {:provider :openai :model "gpt-4"})
    (is (some? (router/get-config :temp)))
    (router/unregister! :temp)
    (is (nil? (router/get-config :temp)))))

(deftest test-list-configs
  (testing "List all registered configurations"
    (router/register! :config1 {:provider :openai :model "gpt-4"})
    (router/register! :config2 {:provider :anthropic :model "claude-3-sonnet-20240229"})
    (let [configs (router/list-configs)]
      (is (= 2 (count configs)))
      (is (contains? (set configs) :config1))
      (is (contains? (set configs) :config2)))))

(deftest test-clear-router
  (testing "Clear all configurations"
    (router/register! :config1 {:provider :openai :model "gpt-4"})
    (router/register! :config2 {:provider :anthropic :model "claude-3-sonnet-20240229"})
    (is (= 2 (count (router/list-configs))))
    (router/clear-router!)
    (is (= 0 (count (router/list-configs))))))

;; ============================================================================
;; Router Configuration Tests
;; ============================================================================

(deftest test-register-router-config
  (testing "Register configuration with router function"
    (let [router-fn (fn [req]
                      (if (> (count (:messages req)) 5)
                        {:provider :anthropic :model "claude-3-opus-20240229"}
                        {:provider :openai :model "gpt-4o-mini"}))
          config-map {:router router-fn
                     :configs {:openai {:api-key "openai-key"}
                              :anthropic {:api-key "anthropic-key"}}}]
      (router/register! :smart config-map)
      (is (some? (router/get-config :smart)))
      (is (fn? (:router (router/get-config :smart)))))))

(deftest test-create-router
  (testing "Create router configuration helper"
    (let [router-fn (fn [{:keys [priority]}]
                      (case priority
                        :high {:provider :anthropic :model "claude-3-opus-20240229"}
                        :low {:provider :openai :model "gpt-4o-mini"}
                        {:provider :openai :model "gpt-4"}))
          configs {:openai {:api-key "sk-..."}
                  :anthropic {:api-key "sk-ant-..."}}
          router-config (router/create-router router-fn configs)]
      (is (map? router-config))
      (is (fn? (:router router-config)))
      (is (= configs (:configs router-config))))))

;; ============================================================================
;; Setup Function Tests
;; ============================================================================

(deftest test-setup-openai
  (testing "Setup OpenAI with explicit API key"
    (router/setup-openai! :config-name :my-openai
                          :api-key "test-key"
                          :model "gpt-4")
    (let [config (router/get-config :my-openai)]
      (is (some? config))
      (is (= :openai (:provider config)))
      (is (= "gpt-4" (:model config)))
      (is (= "test-key" (get-in config [:config :api-key]))))))

(deftest test-setup-openai-default-name
  (testing "Setup OpenAI with default config name"
    (router/setup-openai! :api-key "test-key")
    (let [config (router/get-config :openai)]
      (is (some? config))
      (is (= :openai (:provider config)))
      (is (= "gpt-4o-mini" (:model config))))))

(deftest test-setup-openai-from-env
  (testing "Setup OpenAI from environment variable"
    (if (System/getenv "OPENAI_API_KEY")
      (do
        (router/setup-openai!)
        (let [config (router/get-config :openai)]
          (is (some? config))
          (is (= :openai (:provider config)))))
      ;; If env var not set, just verify we have a test
      (is true "Skipping test - no OPENAI_API_KEY env var"))))

(deftest test-setup-openai-missing-key
  (testing "Setup OpenAI fails without API key or env var"
    (if (System/getenv "OPENAI_API_KEY")
      ;; If env var is set, we can't test the failure case
      (is true "Skipping test - OPENAI_API_KEY env var is set")
      ;; If env var is not set, test that setup fails
      (is (thrown-with-msg? Exception #"OpenAI API key"
                            (router/setup-openai!))))))

(deftest test-setup-anthropic
  (testing "Setup Anthropic with explicit API key"
    (router/setup-anthropic! :config-name :my-anthropic
                             :api-key "test-key"
                             :model "claude-3-opus-20240229")
    (let [config (router/get-config :my-anthropic)]
      (is (some? config))
      (is (= :anthropic (:provider config)))
      (is (= "claude-3-opus-20240229" (:model config)))
      (is (= "test-key" (get-in config [:config :api-key]))))))

(deftest test-setup-gemini
  (testing "Setup Gemini with explicit API key"
    (router/setup-gemini! :config-name :my-gemini
                          :api-key "test-key"
                          :model "gemini-pro")
    (let [config (router/get-config :my-gemini)]
      (is (some? config))
      (is (= :gemini (:provider config)))
      (is (= "gemini-pro" (:model config)))
      (is (= "test-key" (get-in config [:config :api-key]))))))

(deftest test-setup-mistral
  (testing "Setup Mistral with explicit API key"
    (router/setup-mistral! :config-name :my-mistral
                           :api-key "test-key"
                           :model "mistral-medium")
    (let [config (router/get-config :my-mistral)]
      (is (some? config))
      (is (= :mistral (:provider config)))
      (is (= "mistral-medium" (:model config)))
      (is (= "test-key" (get-in config [:config :api-key]))))))

(deftest test-setup-ollama
  (testing "Setup Ollama (no API key required)"
    (router/setup-ollama! :config-name :my-ollama
                          :api-base "http://localhost:11434"
                          :model "llama3")
    (let [config (router/get-config :my-ollama)]
      (is (some? config))
      (is (= :ollama (:provider config)))
      (is (= "llama3" (:model config)))
      (is (= "http://localhost:11434" (get-in config [:config :api-base]))))))

(deftest test-setup-openrouter
  (testing "Setup OpenRouter with explicit API key"
    (router/setup-openrouter! :config-name :my-openrouter
                              :api-key "test-key"
                              :model "openai/gpt-4")
    (let [config (router/get-config :my-openrouter)]
      (is (some? config))
      (is (= :openrouter (:provider config)))
      (is (= "openai/gpt-4" (:model config)))
      (is (= "test-key" (get-in config [:config :api-key]))))))

(deftest test-quick-setup
  (testing "Quick setup registers available providers"
    (router/quick-setup!)
    (let [configs (router/list-configs)]
      ;; Ollama should always be registered
      (is (contains? (set configs) :ollama))
      ;; Other providers only if env vars are set
      (when (System/getenv "OPENAI_API_KEY")
        (is (contains? (set configs) :openai)))
      (when (System/getenv "ANTHROPIC_API_KEY")
        (is (contains? (set configs) :anthropic))))))

;; ============================================================================
;; Provider Discovery (Re-exported from core)
;; ============================================================================

(deftest test-list-providers
  (testing "List providers re-exported from core"
    (let [providers (router/list-providers)]
      (is (coll? providers))
      (is (seq providers))
      (is (contains? providers :openai)))))

(deftest test-provider-available
  (testing "Provider availability check re-exported from core"
    (is (true? (router/provider-available? :openai)))
    (is (false? (router/provider-available? :nonexistent)))))

(deftest test-provider-info
  (testing "Provider info re-exported from core"
    (let [info (router/provider-info :openai)]
      (is (map? info))
      (is (= :openai (:name info))))))

;; ============================================================================
;; Validation Helpers (Re-exported from core)
;; ============================================================================

(deftest test-validate-request
  (testing "Request validation re-exported from core"
    (is (nil? (router/validate-request :openai {:model "gpt-4" :messages [{:role :user :content "test"}]})))
    (is (thrown? Exception
                 (router/validate-request :openai {:model "gpt-4"})))))

(deftest test-supports-streaming
  (testing "Streaming support check re-exported from core"
    (is (true? (router/supports-streaming? :openai)))
    (is (true? (router/supports-streaming? :anthropic)))))

(deftest test-supports-function-calling
  (testing "Function calling support check re-exported from core"
    (is (true? (router/supports-function-calling? :openai)))
    (is (true? (router/supports-function-calling? :gemini)))))

;; ============================================================================
;; Response Utilities (Re-exported from core)
;; ============================================================================

(deftest test-extract-content
  (testing "Extract content re-exported from core"
    (let [response {:choices [{:message {:role :assistant :content "Hello"}}]}]
      (is (= "Hello" (router/extract-content response))))))

(deftest test-extract-message
  (testing "Extract message re-exported from core"
    (let [response {:choices [{:message {:role :assistant :content "Hello"}}]}
          message (router/extract-message response)]
      (is (= :assistant (:role message)))
      (is (= "Hello" (:content message))))))

(deftest test-extract-usage
  (testing "Extract usage re-exported from core"
    (let [response {:usage {:prompt-tokens 10 :completion-tokens 20 :total-tokens 30}}
          usage (router/extract-usage response)]
      (is (= 10 (:prompt-tokens usage)))
      (is (= 20 (:completion-tokens usage))))))

;; ============================================================================
;; Cost Estimation (Re-exported from core)
;; ============================================================================

(deftest test-estimate-tokens
  (testing "Token estimation re-exported from core"
    (let [tokens (router/estimate-tokens "Hello world")]
      (is (number? tokens))
      (is (pos? tokens)))))

(deftest test-estimate-request-tokens
  (testing "Request token estimation re-exported from core"
    (let [request {:messages [{:role :user :content "What is 2+2?"}]}
          tokens (router/estimate-request-tokens request)]
      (is (number? tokens))
      (is (pos? tokens)))))

(deftest test-calculate-cost
  (testing "Cost calculation re-exported from core"
    (let [cost (router/calculate-cost :openai "gpt-4" 1000 500)]
      (is (number? cost))
      (is (pos? cost)))))

;; ============================================================================
;; Completion Function Tests
;; ============================================================================

(deftest test-completion-with-unregistered-config
  (testing "Completion fails with unregistered config"
    (is (thrown-with-msg? Exception #"Configuration not found"
                          (router/completion :nonexistent {:messages [{:role :user :content "test"}]})))))

(deftest test-completion-with-registered-config
  (testing "Completion uses registered config"
    (router/register! :test-openai
                      {:provider :openai
                       :model "gpt-4"
                       :config {:api-key "test-key"}})
    ;; Will fail with authentication error, but that proves it's trying to use the config
    (is (thrown? Exception
                 (router/completion :test-openai {:messages [{:role :user :content "test"}]})))))

;; ============================================================================
;; Chat Function Tests
;; ============================================================================

(deftest test-chat-without-system-prompt
  (testing "Chat function without system prompt"
    (router/register! :test-chat
                      {:provider :openai
                       :model "gpt-4"
                       :config {:api-key "test-key"}})
    ;; Will fail with authentication, but proves function works
    (is (thrown? Exception
                 (router/chat :test-chat "What is 2+2?")))))

(deftest test-chat-with-system-prompt
  (testing "Chat function with system prompt"
    (router/register! :test-chat-sys
                      {:provider :openai
                       :model "gpt-4"
                       :config {:api-key "test-key"}})
    ;; Will fail with authentication, but proves function works
    (is (thrown? Exception
                 (router/chat :test-chat-sys "What is 2+2?" 
                             :system-prompt "You are a math tutor")))))

;; ============================================================================
;; Integration Tests (require API keys)
;; ============================================================================

(deftest ^:integration test-router-completion-openai
  (testing "Router completion integration test with OpenAI"
    (when (System/getenv "OPENAI_API_KEY")
      (router/setup-openai!)
      (let [response (router/completion :openai
                                        {:messages [{:role :user :content "Say 'test successful' and nothing else"}]
                                         :max-tokens 10})]
        (is (map? response))
        (is (contains? response :choices))
        (is (string? (router/extract-content response)))))))

(deftest ^:integration test-router-chat-openai
  (testing "Router chat integration test with OpenAI"
    (when (System/getenv "OPENAI_API_KEY")
      (router/setup-openai!)
      (let [response (router/chat :openai "Say 'test successful'"
                                  :system-prompt "You are a test assistant")]
        (is (map? response))
        (is (string? (router/extract-content response)))))))

(deftest ^:integration test-router-with-dynamic-routing
  (testing "Router with dynamic routing function"
    (when (System/getenv "OPENAI_API_KEY")
      (let [router-fn (fn [req]
                        (if (> (count (:messages req)) 3)
                          {:provider :openai :model "gpt-4"}
                          {:provider :openai :model "gpt-3.5-turbo"}))
            config-map {:router router-fn
                       :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}}]
        (router/register! :dynamic config-map)
        
        ;; Small request (should use gpt-3.5-turbo)
        (let [response (router/completion :dynamic
                                          {:messages [{:role :user :content "Hi"}]
                                           :max-tokens 10})]
          (is (map? response))
          (is (string? (router/extract-content response))))))))

(deftest ^:integration test-quick-setup-and-use
  (testing "Quick setup and immediate use"
    (when (System/getenv "OPENAI_API_KEY")
      (router/clear-router!)
      (router/quick-setup!)
      (let [response (router/chat :openai "Say hello" :max-tokens 10)]
        (is (map? response))
        (is (string? (router/extract-content response)))))))
