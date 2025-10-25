(ns examples.web-integration-streaming
  "Examples of integrating LiteLLM streaming with web frameworks"
  (:require [litellm.core :as llm]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]))

;; ============================================================================
;; Ring with Server-Sent Events (SSE)
;; ============================================================================

(comment
  "Ring SSE streaming example"
  
  (defn sse-stream-handler
    "Stream LLM responses as Server-Sent Events"
    [request]
    (let [prompt (get-in request [:params :prompt])
          ch (llm/completion :openai "gpt-4"
                             {:messages [{:role :user :content prompt}]
                              :stream true
                              :api-key (System/getenv "OPENAI_API_KEY")})]
      
      {:status 200
       :headers {"Content-Type" "text/event-stream"
                 "Cache-Control" "no-cache"
                 "Connection" "keep-alive"}
       :body (fn [output-stream]
               (go-loop []
                 (when-let [chunk (<! ch)]
                   (when-not (streaming/is-error-chunk? chunk)
                     (when-let [content (streaming/extract-content chunk)]
                       (.write output-stream 
                               (.getBytes (str "data: " content "\n\n") 
                                         "UTF-8"))
                       (.flush output-stream)))
                   (recur)))
               (.close output-stream))})))

;; ============================================================================
;; HTTP-Kit with Async Streaming
;; ============================================================================

(comment
  "HTTP-Kit async streaming example"
  
  (require '[org.httpkit.server :as http])
  
  (defn async-stream-handler [request]
    (http/with-channel request channel
      (let [ch (llm/completion :openai "gpt-4"
                               {:messages [{:role :user :content "Hello"}]
                                :stream true
                                :api-key (System/getenv "OPENAI_API_KEY")})]
        
        (go-loop []
          (if-let [chunk (<! ch)]
            (if (streaming/is-error-chunk? chunk)
              (http/send! channel {:status 500 
                                  :body (:message chunk)} 
                         true)  ; close
              (do
                (when-let [content (streaming/extract-content chunk)]
                  (http/send! channel content false))  ; don't close
                (recur)))
            ;; Channel closed
            (http/close channel)))))))

;; ============================================================================
;; Pedestal with SSE
;; ============================================================================

(comment
  "Pedestal SSE streaming example"
  
  (require '[io.pedestal.http.sse :as sse])
  
  (defn streaming-llm-sse [request]
    (let [prompt (get-in request [:query-params :prompt])
          ch (llm/completion :openai "gpt-4"
                             {:messages [{:role :user :content prompt}]
                              :stream true
                              :api-key (System/getenv "OPENAI_API_KEY")})
          event-ch (sse/start-event-stream request)]
      
      (go-loop []
        (when-let [chunk (<! ch)]
          (when-let [content (streaming/extract-content chunk)]
            (sse/send-event event-ch {:name "message"
                                     :data content}))
          (recur))
        (sse/end-event-stream event-ch))
      
      event-ch)))

;; ============================================================================
;; WebSocket Integration (HTTP-Kit)
;; ============================================================================

(comment
  "HTTP-Kit WebSocket streaming example"
  
  (require '[org.httpkit.server :as http]
           '[cheshire.core :as json])
  
  (defn ws-handler [request]
    (http/with-channel request channel
      (if (http/websocket? channel)
        (http/on-receive channel
          (fn [message]
            (let [data (json/parse-string message true)
                  prompt (:prompt data)
                  ch (llm/completion :openai "gpt-4"
                                     {:messages [{:role :user :content prompt}]
                                      :stream true
                                      :api-key (System/getenv "OPENAI_API_KEY")})]
              
              (go-loop []
                (when-let [chunk (<! ch)]
                  (if (streaming/is-error-chunk? chunk)
                    (http/send! channel 
                               (json/generate-string {:error (:message chunk)}))
                    (when-let [content (streaming/extract-content chunk)]
                      (http/send! channel 
                                 (json/generate-string {:content content}))))
                  (recur))))))
        
        (http/send! channel {:status 400 :body "WebSocket expected"})))))

;; ============================================================================
;; Sente (Realtime Communication)
;; ============================================================================

(comment
  "Sente streaming example"
  
  (require '[taoensso.sente :as sente])
  
  (defn handle-llm-stream [{:keys [?data uid send-fn]}]
    (let [prompt (:prompt ?data)
          ch (llm/completion :openai "gpt-4"
                             {:messages [{:role :user :content prompt}]
                              :stream true
                              :api-key (System/getenv "OPENAI_API_KEY")})]
      
      (go-loop []
        (when-let [chunk (<! ch)]
          (when-let [content (streaming/extract-content chunk)]
            (send-fn uid [:llm/chunk {:content content}]))
          (recur))
        (send-fn uid [:llm/complete {}])))))

;; ============================================================================
;; Architecture Best Practices
;; ============================================================================

(comment
  "Service Layer Pattern - Return channels, don't consume them"
  
  ;; ❌ DON'T: Consume channel in service layer
  (defn generate-response-bad [prompt]
    (let [ch (llm/completion :openai "gpt-4" 
                             {:messages [{:role :user :content prompt}]
                              :stream true})]
      (streaming/collect-stream ch)))  ; Blocks!
  
  ;; ✅ DO: Return channel from service layer
  (defn generate-response [prompt]
    (llm/completion :openai "gpt-4"
                    {:messages [{:role :user :content prompt}]
                     :stream true
                     :api-key (System/getenv "OPENAI_API_KEY")}))
  
  ;; Handler consumes the channel
  (defn handler [request]
    (let [ch (generate-response (:prompt request))]
      ;; Now handler manages the streaming
      (stream-to-response ch))))

;; ============================================================================
;; Error Boundary Pattern
;; ============================================================================

(defn with-streaming-error-handling 
  "Wraps a streaming channel with error handling"
  [ch response-fn error-fn]
  (go-loop []
    (when-let [chunk (<! ch)]
      (if (streaming/is-error-chunk? chunk)
        (error-fn chunk)
        (response-fn chunk))
      (recur))))

(comment
  "Usage in handler"
  
  (defn handler [request]
    (let [ch (llm/completion :openai "gpt-4"
                             {:messages [{:role :user :content "Hello"}]
                              :stream true
                              :api-key (System/getenv "OPENAI_API_KEY")})
          output-stream (:output-stream request)]
      (with-streaming-error-handling ch
        (fn [chunk]  ; success
          (when-let [content (streaming/extract-content chunk)]
            (write-to-stream output-stream content)))
        (fn [error]  ; error
          (write-error output-stream error))))))

;; ============================================================================
;; Common Gotchas
;; ============================================================================

(comment
  "1. Don't Block in go Blocks"
  
  ;; ❌ DON'T: Block in go blocks
  (go
    (let [result @(http/get "...")  ; Blocking!
          ch (llm/completion ...)]
      ...))
  
  ;; ✅ DO: Use go-loop with <! for channels
  (go
    (let [result (<! (http/get-async "..."))
          ch (llm/completion ...)]
      ...)))

(comment
  "2. Always Close Channels"
  
  ;; ❌ DON'T: Leave channels open
  (let [ch (llm/completion :stream true ...)]
    (go-loop []
      (when-let [chunk (<! ch)]
        (process chunk)
        (recur)))
    ;; Channel might leak if error occurs!
    )
  
  ;; ✅ DO: Use try-finally or error handling
  (let [ch (llm/completion :stream true ...)]
    (go
      (try
        (loop []
          (when-let [chunk (<! ch)]
            (process chunk)
            (recur)))
        (finally
          (streaming/close-stream! ch))))))

(comment
  "3. Don't Share Channels Across Requests"
  
  ;; ❌ DON'T: Reuse streaming channels
  (def shared-channel (atom nil))
  
  (defn handler [request]
    (when (nil? @shared-channel)
      (reset! shared-channel (llm/completion :stream true ...)))
    ;; Bad! Multiple requests share same stream
    @shared-channel)
  
  ;; ✅ DO: Create new channel per request
  (defn handler [request]
    (llm/completion :stream true ...)))  ; Fresh channel each time

(comment
  "4. Timeout Long-Running Streams"
  
  (require '[clojure.core.async :refer [timeout alt!]])
  
  (go-loop []
    (alt!
      ch ([chunk]
          (when chunk
            (process chunk)
            (recur)))
      
      (timeout 30000) ([_]
                       (println "Stream timeout after 30s")
                       (streaming/close-stream! ch)))))

;; ============================================================================
;; Testing Streaming Code
;; ============================================================================

(comment
  "Unit Testing with Channels"
  
  (require '[clojure.test :refer :all]
           '[clojure.core.async :refer [chan go >! close!]])
  
  (deftest test-stream-processing
    (let [test-ch (chan)
          result (atom [])]
      
      ;; Populate test channel
      (go
        (>! test-ch {:choices [{:delta {:content "Hello"}}]})
        (>! test-ch {:choices [{:delta {:content " World"}}]})
        (close! test-ch))
      
      ;; Test processing
      (let [collected (streaming/collect-stream test-ch)]
        (is (= "Hello World" (:content collected)))))))

;; ============================================================================
;; Performance Considerations
;; ============================================================================

(comment
  "Performance Tips:
  
  1. **Buffer Size**: Adjust channel buffer sizes based on latency requirements
  2. **Backpressure**: Use buffering to handle slow consumers
  3. **Connection Pooling**: Reuse HTTP connections for multiple streaming requests
  4. **Memory**: Monitor memory usage with long-running streams
  5. **Timeouts**: Always implement timeouts for streaming operations")
