(ns litellm.streaming-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [chan go >! <!! close!]]
            [litellm.streaming :as streaming]))

;; ============================================================================
;; Basic Tests
;; ============================================================================

(deftest test-stream-error
  (testing "Create error chunk"
    (let [error (streaming/stream-error "openai" "Test error")]
      (is (= :error (:type error)))
      (is (= "openai" (:provider error)))
      (is (= "Test error" (:message error))))))

(deftest test-is-error-chunk
  (testing "Identify error chunks"
    (is (true? (streaming/is-error-chunk? {:type :error})))
    (is (false? (streaming/is-error-chunk? {})))))

(deftest test-extract-content
  (testing "Extract content from chunk"
    (let [chunk {:choices [{:delta {:content "Hello"}}]}]
      (is (= "Hello" (streaming/extract-content chunk)))))
  
  (testing "Extract nil from error chunk"
    (let [chunk {:type :error}]
      (is (nil? (streaming/extract-content chunk))))))

(deftest test-collect-stream
  (testing "Collect chunks from stream"
    (let [test-ch (chan)]
      (go
        (>! test-ch {:choices [{:delta {:content "Hello"}}]})
        (>! test-ch {:choices [{:delta {:content " World"}}]})
        (close! test-ch))
      
      (let [result (streaming/collect-stream test-ch)]
        (is (= "Hello World" (:content result)))
        (is (= 2 (count (:chunks result))))))))

(deftest test-parse-sse-line
  (testing "Parse valid SSE line"
    (let [parsed (streaming/parse-sse-line 
                   "data: {\"test\":\"value\"}" 
                   cheshire.core/decode)]
      (is (some? parsed))
      (is (= {:test "value"} parsed))))
  
  (testing "Skip [DONE] marker"
    (let [parsed (streaming/parse-sse-line "data: [DONE]" cheshire.core/decode)]
      (is (nil? parsed)))))
