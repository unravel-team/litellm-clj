(ns litellm.errors-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [litellm.errors :as errors]
            [litellm.core :as core]
            [litellm.offline :as offline]
            [clojure.core.async :as async]
            [hato.client :as http]))

(use-fixtures :each offline/guard)

(deftest credentials-do-not-survive-validation-or-diagnostics
  (let [secret "unique-fake-credential"
        request {:api-key secret :nested [{"Authorization" secret}
                                          {:aws-secret-access-key secret
                                           :session_token secret :password secret}]}
        exceptions [(errors/invalid-request "Invalid request" :request request
                                            :errors {:value secret})
                    (errors/invalid-config "Invalid config" :config request)
                    (ex-info "Legacy error" {:type :litellm/internal-error
                                             :context {:nested [request] :headers {"custom" secret}}})]]
    (doseq [e exceptions]
      (is (not (str/includes? (pr-str (errors/error-details e)) secret)))
      (when-not (= "Legacy error" (.getMessage e))
        (is (not (str/includes? (pr-str e) secret)))))))

(deftest transport-errors-do-not-echo-provider-or-cause-content
  (let [secret "opaque-fake-provider-credential"
        e (errors/http-status->error 401 "openai" (str "Invalid key " secret))
        connection (try (errors/wrap-http-errors "openai"
                                                 #(throw (java.net.ConnectException. secret)))
                        (catch Exception e e))]
    (doseq [error [e connection]]
      (is (not (str/includes? (pr-str error) secret)))
      (is (not (str/includes? (pr-str (errors/error-details error)) secret))))))

(deftest generic-and-future-errors-omit-opaque-external-text
  (let [secret "opaque-future-credential"]
    (doseq [cause [(IllegalStateException. secret)
                   (java.util.concurrent.ExecutionException. (Exception. secret))]]
      (let [e (try (errors/wrap-http-errors "openai" #(throw cause))
                   (catch Exception e e))]
        (is (not (str/includes? (pr-str e) secret)))))
    (let [typed (errors/rate-limit "openai" "Rate limited" :retry-after 17)
          e (try (errors/wrap-http-errors "openai" #(throw typed))
                 (catch Exception e e))]
      (is (errors/rate-limit-error? e))
      (is (= 429 (:http-status (ex-data e))))
      (is (= 17 (:retry-after (ex-data e)))))))

(deftest streaming-transport-failures-omit-opaque-external-text
  (let [secret "opaque-stream-credential"]
    (doseq [provider [:openai :anthropic :gemini :azure :openrouter :deepseek]]
      (with-redefs [http/post (fn [& _] (throw (IllegalStateException. secret)))]
        (let [ch (core/completion provider "fake-model"
                                  {:messages [{:role :user :content "test"}] :stream true}
                                  {:api-key "fake" :api-base "https://trusted.invalid" :deployment "fake"})
              [chunk port] (async/alts!! [ch (async/timeout 3000)])]
          (is (= ch port))
          (is (errors/error-chunk? chunk))
          (is (not (str/includes? (pr-str chunk) secret)))
          (let [[end end-port] (async/alts!! [ch (async/timeout 3000)])]
            (is (= ch end-port))
            (is (nil? end))))))))

(deftest validation-errors-remain-safe-when-logged
  (let [secret "fake-validation-secret"
        output (with-out-str
                 (try (core/completion :openai "gpt-4" {:api-key secret})
                      (catch Exception e
                        (prn e)
                        (prn (errors/error-details e)))))]
    (is (not (str/includes? output secret)))))
