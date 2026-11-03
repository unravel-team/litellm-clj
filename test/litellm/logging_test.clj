(ns litellm.logging-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [cheshire.core :as json]
            [hato.client :as http]
            [litellm.core :as core]
            [litellm.wrappers :as wrappers]
            [litellm.streaming :as streaming]
            [litellm.providers.mistral :as mistral]
            [litellm.providers.openrouter :as openrouter]
            [litellm.providers.ollama :as ollama]
            [litellm.offline :as offline]))

(use-fixtures :each offline/guard)

(defn capture-logs [f]
  (let [entries (atom [])
        logger (reify impl/Logger
                 (enabled? [_ _] true)
                 (write! [_ level throwable message]
                   (swap! entries conj [level throwable message])))
        factory (reify impl/LoggerFactory
                  (name [_] "test-capture")
                  (get-logger [_ _] logger))]
    (binding [log/*logger-factory* factory]
      (f))
    @entries))

(defn drain-stream [ch]
  (loop []
    (let [[value port] (async/alts!! [ch (async/timeout 3000)])]
      (is (= ch port) "Fake stream must close before timeout")
      (when (and (= ch port) value) (recur)))))

(deftest retry-and-fallback-diagnostics-omit-external-text
  (let [sentinel "private-retry-credential"
        fail (fn [& _] (throw (Exception. sentinel)))
        failures (atom [])
        entries (capture-logs
                 #(doseq [invoke [(fn [] (wrappers/with-retry :fake {} fail
                                                              {:max-attempts 2 :backoff-ms 0}))
                                  (fn [] (wrappers/with-fallback [:fake] {} fail))]]
                    (try (invoke)
                         (catch Exception e
                           (swap! failures conj e)))))]
    (is (seq entries))
    (is (not (str/includes? (pr-str entries) sentinel)))
    ;; The fallback owns its aggregate exception data, not the caller's original exception.
    (is (not (str/includes? (pr-str (ex-data (last @failures))) sentinel)))))

(deftest malformed-stream-logs-contain-metadata-not-content
  (let [sentinel "private-malformed-chunk"
        line (str "data: {\"content\":\"" sentinel "\" invalid}")]
    (doseq [parse [#(streaming/parse-sse-line line json/decode)
                   #(mistral/parse-sse-line line)
                   #(openrouter/parse-sse-line line)
                   #(ollama/handle-streaming-response {:body line :ollama-request-type :chat} identity)]]
      (let [entries (capture-logs parse)]
        (is (seq entries) "Parse failures still produce diagnostics")
        (is (not (str/includes? (pr-str entries) sentinel)))))))

(deftest streaming-logs-omit-prompts-tools-headers-and-raw-lines
  (doseq [provider [:anthropic :gemini]]
    (let [sentinel "private-stream-content"
          entries (capture-logs
                   #(with-redefs [http/post (fn [_ _]
                                             {:status 200
                                              :headers {"x-sensitive" sentinel}
                                              :body (java.io.ByteArrayInputStream.
                                                     (.getBytes (str "data: {\"content\":\"" sentinel "\" invalid}\n") "UTF-8"))})]
                      (drain-stream
                       (core/completion provider "fake-model"
                                        {:messages [{:role :user :content sentinel}]
                                         :tools [{:type "function" :function {:name "fake" :description sentinel
                                                                             :parameters {:type "object"}}}]
                                         :stream true}
                                        {:api-key "fake-key"}))))]
      (is (seq entries))
      (is (not (str/includes? (pr-str entries) sentinel))))))
