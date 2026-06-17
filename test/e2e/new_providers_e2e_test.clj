(ns e2e.new-providers-e2e-test
  "Live E2E tests for OpenAI-compatible direct providers added after initial suite."
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [e2e.run-e2e-tests-test :refer [calculator-tool
                                             execute-calculator
                                             testing-with-skippable-provider-errors]]
            [litellm.core :as litellm]
            [litellm.streaming :as streaming]))

(def ^:private request-timeout-ms 45000)
(def ^:private stream-timeout-ms 45000)

(defn- env
  [name]
  (let [value (System/getenv name)]
    (when-not (str/blank? value)
      value)))

(defn- env-or
  [name default]
  (or (env name) default))

(defn- kimi-api-key
  []
  (or (env "MOONSHOT_API_KEY")
      (env "KIMI_API_KEY")))

(defn- text?
  [value]
  (and (string? value) (not (str/blank? value))))

(defn- throw-stream-error!
  [chunk]
  (throw (ex-info (or (:message chunk) "Streaming E2E error")
                  (cond-> (dissoc chunk :type :message)
                    (:error-type chunk) (assoc :type (:error-type chunk))))))

(defn- collect-stream-chunks!
  [ch]
  (let [deadline (+ (System/currentTimeMillis) stream-timeout-ms)]
    (loop [chunks []]
      (let [remaining (- deadline (System/currentTimeMillis))]
        (if (pos? remaining)
          (let [[value port] (async/alts!! [ch (async/timeout (min 1000 remaining))])]
            (cond
              (= port ch)
              (cond
                (nil? value) chunks
                (streaming/is-error-chunk? value) (throw-stream-error! value)
                :else (recur (conj chunks value)))

              :else
              (recur chunks)))
          chunks)))))

(defn- assert-standard-response!
  [response label]
  (is (map? response) (str label " response should be a map"))
  (is (seq (:choices response)) (str label " response should include choices"))
  (is (map? (get-in response [:choices 0 :message]))
      (str label " response should include first message"))
  response)

(defn- assert-answer-like!
  [response label]
  (let [message (get-in response [:choices 0 :message])]
    (is (or (text? (:content message))
            (text? (:reasoning-content message))
            (seq (:tool-calls message)))
        (str label " should contain content, reasoning, or tool calls"))))

(defn- assert-stream-response!
  [ch label]
  (is (some? ch) (str label " stream channel should not be nil"))
  (let [chunks (if ch (collect-stream-chunks! ch) [])]
    (is (seq chunks) (str label " should produce at least one chunk"))
    (is (every? map? chunks) (str label " chunks should be maps"))
    chunks))

(defn- assert-json-status-ok!
  [response]
  (let [content (litellm/extract-content response)
        parsed (json/decode content true)]
    (is (= "ok" (:status parsed)) "JSON mode should return {:status \"ok\"}")))

(defn- maybe-run-kimi-tool-call!
  [model api-key]
  (testing "Optional tool calling"
    (let [response (litellm/kimi-completion
                    model
                    {:messages [{:role :user
                                 :content "If useful, call calculate to add 5 and 3, then answer."}]
                     :tools [calculator-tool]
                     :tool-choice :auto
                     :max-tokens 256}
                    :api-key api-key
                    :timeout request-timeout-ms)
          tool-calls (get-in response [:choices 0 :message :tool-calls])]
      (assert-standard-response! response "Kimi tool-call")
      (if (seq tool-calls)
        (let [tool-call (first tool-calls)
              arguments (json/decode (get-in tool-call [:function :arguments]) true)
              result (execute-calculator arguments)
              final-response (litellm/kimi-completion
                              model
                              {:messages [{:role :user
                                           :content "If useful, call calculate to add 5 and 3, then answer."}
                                          (get-in response [:choices 0 :message])
                                          {:role :tool
                                           :tool-call-id (:id tool-call)
                                           :content result}]
                               :max-tokens 128}
                              :api-key api-key
                              :timeout request-timeout-ms)]
          (is (= "function" (:type tool-call)) "Tool call type should be function")
          (is (= "calculate" (get-in tool-call [:function :name])) "Should call calculator tool")
          (assert-standard-response! final-response "Kimi tool result")
          (assert-answer-like! final-response "Kimi tool result"))
        (println "  ⚠️  Kimi optional tool-call check skipped - model answered directly")))))

(deftest ^:e2e test-deepseek-direct-provider
  (testing-with-skippable-provider-errors "DeepSeek direct provider E2E tests"
    (if-let [api-key (env "DEEPSEEK_API_KEY")]
      (let [model (env-or "DEEPSEEK_E2E_MODEL" "deepseek-v4-flash")]
        (println "\n🧪 Testing :deepseek direct provider...")

        (testing "Non-streaming completion"
          (let [response (litellm/deepseek-completion
                          model
                          {:messages [{:role :user
                                       :content "Say deepseek-ok and nothing else."}]
                           :thinking {:type :disabled}
                           :max-tokens 32}
                          :api-key api-key
                          :timeout request-timeout-ms)]
            (assert-standard-response! response "DeepSeek basic")
            (assert-answer-like! response "DeepSeek basic")))

        (testing "Streaming completion"
          (let [ch (litellm/deepseek-completion
                    model
                    {:messages [{:role :user :content "Count one number only."}]
                     :thinking {:type :disabled}
                     :max-tokens 32
                     :stream true}
                    :api-key api-key
                    :timeout request-timeout-ms)]
            (assert-stream-response! ch "DeepSeek streaming")))

        (testing "JSON mode"
          (let [response (litellm/deepseek-completion
                          model
                          {:messages [{:role :user
                                       :content "Return JSON only: {\"status\":\"ok\"}."}]
                           :thinking {:type :disabled}
                           :response-format {:type :json-object}
                           :max-tokens 80}
                          :api-key api-key
                          :timeout request-timeout-ms)]
            (assert-standard-response! response "DeepSeek JSON")
            (assert-json-status-ok! response)))

        (println "✅ :deepseek direct provider tests passed!\n"))
      (do
        (println "⚠️  DeepSeek tests skipped - DEEPSEEK_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e test-kimi-direct-provider
  (testing-with-skippable-provider-errors "Kimi direct provider E2E tests"
    (if-let [api-key (kimi-api-key)]
      (let [model (env-or "KIMI_E2E_MODEL" "kimi-k2.6")]
        (println "\n🧪 Testing :kimi direct provider...")

        (testing "Non-streaming completion"
          (let [response (litellm/kimi-completion
                          model
                          {:messages [{:role :user
                                       :content "Say kimi-ok and nothing else."}]
                           :max-tokens 32}
                          :api-key api-key
                          :timeout request-timeout-ms)]
            (assert-standard-response! response "Kimi basic")
            (assert-answer-like! response "Kimi basic")))

        (testing "Streaming completion"
          (let [ch (litellm/kimi-completion
                    model
                    {:messages [{:role :user :content "Count one number only."}]
                     :max-tokens 32
                     :stream true}
                    :api-key api-key
                    :timeout request-timeout-ms)]
            (assert-stream-response! ch "Kimi streaming")))

        (maybe-run-kimi-tool-call! model api-key)

        (println "✅ :kimi direct provider tests passed!\n"))
      (do
        (println "⚠️  Kimi tests skipped - MOONSHOT_API_KEY or KIMI_API_KEY not set")
        (is true "Skipped - API key not set")))))

(deftest ^:e2e test-zai-direct-provider
  (testing-with-skippable-provider-errors "Z.AI direct provider E2E tests"
    (if-let [api-key (env "ZAI_API_KEY")]
      (let [model (env-or "ZAI_E2E_MODEL" "glm-5.2")
            thinking {:type :enabled :clear-thinking true}]
        (println "\n🧪 Testing :zai direct provider...")

        (testing "Non-streaming completion with thinking enabled"
          (let [response (litellm/zai-completion
                          model
                          {:messages [{:role :user
                                       :content "Think briefly, then say zai-ok and nothing else."}]
                           :thinking thinking
                           :reasoning-effort :low
                           :max-tokens 128}
                          :api-key api-key
                          :timeout request-timeout-ms)]
            (assert-standard-response! response "Z.AI basic")
            (assert-answer-like! response "Z.AI basic")))

        (testing "Streaming completion with thinking enabled"
          (let [ch (litellm/zai-completion
                    model
                    {:messages [{:role :user
                                 :content "Think briefly, then count one number only."}]
                     :thinking thinking
                     :reasoning-effort :low
                     :max-tokens 128
                     :stream true}
                    :api-key api-key
                    :timeout request-timeout-ms)]
            (assert-stream-response! ch "Z.AI streaming")))

        (println "✅ :zai direct provider tests passed!\n"))
      (do
        (println "⚠️  Z.AI tests skipped - ZAI_API_KEY not set")
        (is true "Skipped - API key not set")))))
