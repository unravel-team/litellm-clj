(ns litellm.offline
  "Unit-test HTTP guard. Fakes replace the public HTTP functions above this boundary."
  (:require [clojure.test :as test]
            [hato.client :as http]))

(defn guard [f]
  (let [attempts (atom 0)]
    (with-redefs [http/request* (fn [& _]
                                (swap! attempts inc)
                                (throw (ex-info "Unmocked HTTP request in offline tests" {})))]
      (try
        (f)
        (finally
          (test/is (zero? @attempts) "Unit tests must not attempt unmocked HTTP requests"))))))
