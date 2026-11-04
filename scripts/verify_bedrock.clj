(require '[clojure.test :as test]
         'litellm.providers.bedrock-test)

;; This optional suite contains transformations only, not AWS client calls.
(let [{:keys [fail error]} (test/run-tests 'litellm.providers.bedrock-test)]
  (System/exit (+ fail error)))
