(ns litellm.errors-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [litellm.errors :as errors]
            [litellm.core :as core]
            [litellm.offline :as offline]))

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

(deftest validation-errors-remain-safe-when-logged
  (let [secret "fake-validation-secret"
        output (with-out-str
                 (try (core/completion :openai "gpt-4" {:api-key secret})
                      (catch Exception e
                        (prn e)
                        (prn (errors/error-details e)))))]
    (is (not (str/includes? output secret)))))
