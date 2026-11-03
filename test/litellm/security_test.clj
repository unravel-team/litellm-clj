(ns litellm.security-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [litellm.core :as core]
            [litellm.router :as router]
            [litellm.providers.core :as providers]
            [litellm.offline :as offline]
            [hato.client :as http]))

(use-fixtures :each offline/guard)

(deftest declared-dependencies-use-exact-releases
  (let [deps (edn/read-string (slurp "deps.edn"))]
    (doseq [dependency-map (concat [(:deps deps)]
                                  (mapcat #(keep % [:deps :extra-deps :replace-deps])
                                          (vals (:aliases deps))))
            [coordinate version] dependency-map]
      (is (boolean (re-matches #"[0-9][0-9A-Za-z.\-]*" (:mvn/version version "")))
          (str coordinate " must use an exact Maven release"))
      (is (not (re-find #"(?i)SNAPSHOT" (:mvn/version version "")))))))

(deftest request-destinations-are-not-trusted
  (let [calls (atom 0)
        request {:messages [{:role :user :content "test"}]
                 :api-base "https://attacker.invalid"}
        config {:api-key "fake-trusted-key"}]
    (with-redefs [providers/make-request (fn [& _] (swap! calls inc) (delay {}))
                  providers/make-streaming-request (fn [& _] (swap! calls inc))
                  providers/make-embedding-request (fn [& _] (swap! calls inc) (delay {}))]
      (doseq [invoke [#(core/completion :openai "gpt-4" request config)
                      #(core/completion :openai "gpt-4" (assoc request :stream true) config)
                      #(core/embedding :openai "text-embedding-3-small"
                                       {:input "test" :api-base "http://169.254.169.254"} config)
                      #(do (router/register! :security-test {:provider :openai :model "gpt-4" :config config})
                           (router/completion :security-test request))]]
        (is (thrown-with-msg? Exception #"trusted configuration" (invoke))))
      (is (zero? @calls)))
    (router/unregister! :security-test)))

(deftest custom-endpoints-require-valid-trusted-configuration
  (let [urls (atom [])
        request {:messages [{:role :user :content "test"}]}]
    (with-redefs [http/post (fn [url _] (swap! urls conj url)
                             (delay {:status 200 :body {:choices []}}))]
      (doseq [base ["file:///tmp/socket" "https:///missing-host" "https://user:pass@proxy.invalid"
                    "https://proxy.invalid?key=fake" "https://proxy.invalid#fragment"]]
        (is (thrown-with-msg? Exception #"Invalid API base" 
                              (core/completion :openai "gpt-4" request {:api-base base}))))
      (is (empty? @urls))
      (core/completion :openai "gpt-4" request {:api-base "https://proxy.invalid/v1" :api-key "fake"})
      (is (= ["https://proxy.invalid/v1/chat/completions"] @urls)))))
