# Streaming Guide for LiteLLM-CLJ

## Overview

This guide covers how to use streaming responses in litellm-clj, including integration with web servers and architectural best practices.

## Table of Contents

1. [Basic Streaming](#basic-streaming)
2. [HTTP Integration](#http-integration)
3. [WebSocket Integration](#websocket-integration)
4. [Architecture Patterns](#architecture-patterns)
5. [Common Gotchas](#common-gotchas)

---

## Basic Streaming

### Simple Channel-Based Streaming

```clojure
(require '[litellm.core :as llm]
         '[clojure.core.async :refer [go-loop <!]]
         '[litellm.streaming :as streaming])

;; Get a channel of streaming chunks
(let [ch (llm/completion 
           :model "openai/gpt-4"
           :messages [{:role :user :content "Write a story"}]
           :stream true)]
  
  ;; Consume the channel
  (go-loop []
    (when-let [chunk (<! ch)]
      (when-let [content (streaming/extract-content chunk)]
        (print content)
        (flush))
      (recur))))
```

### Callback-Based Streaming

```clojure
;; Using callbacks for simpler cases
(let [ch (llm/completion :model "openai/gpt-4"
                         :messages [{:role :user :content "Hello"}]
                         :stream true)]
  (streaming/consume-stream-with-callbacks ch
    ;; on-chunk
    (fn [chunk]
      (when-let [content (streaming/extract-content chunk)]
        (print content)
        (flush)))
    ;; on-complete
    (fn [final-response]
      (println "\n\nDone!" 
               "Tokens:" (get-in final-response [:usage :total-tokens])))
    ;; on-error
    (fn [error]
      (println "Error:" (:message error)))))
```

---

## HTTP Integration

### Ring with Server-Sent Events (SSE)

```clojure
(ns myapp.handlers
  (:require [ring.util.response :as response]
            [litellm.core :as llm]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]
            [clojure.string :as str]))

(defn sse-stream-handler
  "Stream LLM responses as Server-Sent Events"
  [request]
  (let [prompt (get-in request [:params :prompt])
        ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content prompt}]
             :stream true)]
    
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
             (.close output-stream))}))
```

### HTTP-Kit with Async Streaming

```clojure
(ns myapp.server
  (:require [org.httpkit.server :as http]
            [litellm.core :as llm]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]))

(defn async-stream-handler [request]
  (http/with-channel request channel
    (let [ch (llm/completion 
               :model "openai/gpt-4"
               :messages [{:role :user :content "Hello"}]
               :stream true)]
      
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
          (http/close channel))))))
```

### Pedestal with SSE

```clojure
(ns myapp.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.sse :as sse]
            [litellm.core :as llm]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]))

(defn streaming-llm-sse [request]
  (let [prompt (get-in request [:query-params :prompt])
        ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content prompt}]
             :stream true)
        event-ch (sse/start-event-stream request)]
    
    (go-loop []
      (when-let [chunk (<! ch)]
        (when-let [content (streaming/extract-content chunk)]
          (sse/send-event event-ch {:name "message"
                                   :data content}))
        (recur))
      (sse/end-event-stream event-ch))
    
    event-ch))
```

---

## WebSocket Integration

### HTTP-Kit WebSocket

```clojure
(ns myapp.websocket
  (:require [org.httpkit.server :as http]
            [litellm.core :as llm]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]
            [cheshire.core :as json]))

(defn ws-handler [request]
  (http/with-channel request channel
    (if (http/websocket? channel)
      (http/on-receive channel
        (fn [message]
          (let [data (json/parse-string message true)
                prompt (:prompt data)
                ch (llm/completion 
                     :model "openai/gpt-4"
                     :messages [{:role :user :content prompt}]
                     :stream true)]
            
            (go-loop []
              (when-let [chunk (<! ch)]
                (if (streaming/is-error-chunk? chunk)
                  (http/send! channel 
                             (json/generate-string {:error (:message chunk)}))
                  (when-let [content (streaming/extract-content chunk)]
                    (http/send! channel 
                               (json/generate-string {:content content}))))
                (recur))))))
      
      (http/send! channel {:status 400 :body "WebSocket expected"}))))
```

### Sente (Realtime Communication)

```clojure
(ns myapp.sente
  (:require [taoensso.sente :as sente]
            [litellm.core :as llm]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]))

(defn handle-llm-stream [{:keys [?data uid send-fn]}]
  (let [prompt (:prompt ?data)
        ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content prompt}]
             :stream true)]
    
    (go-loop []
      (when-let [chunk (<! ch)]
        (when-let [content (streaming/extract-content chunk)]
          (send-fn uid [:llm/chunk {:content content}]))
        (recur))
      (send-fn uid [:llm/complete {}]))))
```

---

## Architecture Patterns

### 1. Layer Separation

```
┌─────────────────────────────┐
│   HTTP/WebSocket Handler    │ ← Manages channels, sends to client
└──────────────┬──────────────┘
               │
               ↓
┌─────────────────────────────┐
│   Service/Use Case Layer    │ ← Returns channels, business logic
└──────────────┬──────────────┘
               │
               ↓
┌─────────────────────────────┐
│   LiteLLM Client Layer      │ ← litellm.core/completion
└─────────────────────────────┘
```

### 2. Service Layer Pattern

```clojure
(ns myapp.services.llm
  (:require [litellm.core :as llm]
            [litellm.streaming :as streaming]))

;; ❌ DON'T: Consume channel in service layer
(defn generate-response-bad [prompt]
  (let [ch (llm/completion :model "..." :messages [...] :stream true)]
    ;; Don't consume here!
    (streaming/collect-stream ch)))  ; Blocks!

;; ✅ DO: Return channel from service layer
(defn generate-response [prompt]
  (llm/completion 
    :model "openai/gpt-4"
    :messages [{:role :user :content prompt}]
    :stream true))

;; Handler consumes the channel
(defn handler [request]
  (let [ch (generate-response (:prompt request))]
    ;; Now handler manages the streaming
    (stream-to-response ch)))
```

### 3. Error Boundary Pattern

```clojure
(defn with-streaming-error-handling [ch response-fn error-fn]
  "Wraps a streaming channel with error handling"
  (go-loop []
    (when-let [chunk (<! ch)]
      (if (streaming/is-error-chunk? chunk)
        (error-fn chunk)
        (response-fn chunk))
      (recur))))

;; Usage in handler
(defn handler [request]
  (let [ch (llm/completion :stream true ...)
        output-stream (:output-stream request)]
    (with-streaming-error-handling ch
      (fn [chunk]  ; success
        (when-let [content (streaming/extract-content chunk)]
          (write-to-stream output-stream content)))
      (fn [error]  ; error
        (write-error output-stream error)))))
```

### 4. Buffering Strategy

```clojure
;; Control backpressure with buffer sizes
(defn create-streaming-request [prompt]
  (let [ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content prompt}]
             :stream true)]
    ;; Re-buffer for slower consumers
    (streaming/create-stream-channel :buffer-size 10)))
```

---

## Common Gotchas

### 1. Don't Block in go Blocks

```clojure
;; ❌ DON'T: Block in go blocks
(go
  (let [result @(http/get "...")  ; Blocking!
        ch (llm/completion ...)]
    ...))

;; ✅ DO: Use go-loop with <! for channels
(go
  (let [result (<! (http/get-async "..."))
        ch (llm/completion ...)]
    ...))
```

### 2. Always Close Channels

```clojure
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
        (streaming/close-stream! ch)))))
```

### 3. Handle nil vs Closed Channel

```clojure
;; nil from channel means channel is closed
(go-loop []
  (when-let [chunk (<! ch)]  ; nil = closed
    (if (streaming/is-error-chunk? chunk)
      (handle-error chunk)
      (process chunk))
    (recur)))
```

### 4. Don't Share Channels Across Requests

```clojure
;; ❌ DON'T: Reuse streaming channels
(def shared-channel (atom nil))

(defn handler [request]
  (when (nil? @shared-channel)
    (reset! shared-channel (llm/completion :stream true ...)))
  ;; Bad! Multiple requests share same stream
  @shared-channel)

;; ✅ DO: Create new channel per request
(defn handler [request]
  (llm/completion :stream true ...))  ; Fresh channel each time
```

### 5. Timeout Long-Running Streams

```clojure
(require '[clojure.core.async :refer [timeout alt!]])

(go-loop []
  (alt!
    ch ([chunk]
        (when chunk
          (process chunk)
          (recur)))
    
    (timeout 30000) ([_]
                     (println "Stream timeout after 30s")
                     (streaming/close-stream! ch))))
```

---

## Testing Streaming Code

### Unit Testing with Channels

```clojure
(ns myapp.streaming-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan go >! close!]]
            [litellm.streaming :as streaming]))

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
      (is (= "Hello World" (:content collected))))))
```

---

## Performance Considerations

1. **Buffer Size**: Adjust channel buffer sizes based on your latency requirements
2. **Backpressure**: Use buffering to handle slow consumers
3. **Connection Pooling**: Reuse HTTP connections for multiple streaming requests
4. **Memory**: Monitor memory usage with long-running streams
5. **Timeouts**: Always implement timeouts for streaming operations

---

## Additional Resources

- [examples/streaming_example.clj](../examples/streaming_example.clj) - Basic examples
- [Ring Async](https://github.com/ring-clojure/ring/wiki/Async) - Ring async handling
- [core.async Guide](https://clojure.github.io/core.async/) - Core.async documentation
- [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events) - SSE specification
