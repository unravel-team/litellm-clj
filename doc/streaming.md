# Streaming Guide

LiteLLM provides built-in support for streaming responses from LLM providers using `core.async` channels.

## Overview

Streaming allows you to receive response chunks as they're generated, rather than waiting for the complete response. This provides:

- ✅ Better user experience with progressive rendering
- ✅ Lower perceived latency
- ✅ Ability to process partial results
- ✅ Early cancellation support

## Quick Start

### Basic Streaming

```clojure
(require '[litellm.core :as core]
         '[litellm.streaming :as streaming]
         '[clojure.core.async :refer [go-loop <!]])

;; Request streaming response
(def ch (core/completion :openai "gpt-4"
          {:messages [{:role :user :content "Write a story"}]
           :stream true}
          {:api-key "sk-..."}))

;; Consume with go-loop
(go-loop []
  (when-let [chunk (<! ch)]
    (when-let [content (streaming/extract-content chunk)]
      (print content)
      (flush))
    (recur)))
```

### Using Router API

```clojure
(require '[litellm.router :as router])

(router/quick-setup!)

(def ch (router/completion :openai
          {:messages [{:role :user :content "Explain quantum physics"}]
           :stream true}))

(go-loop []
  (when-let [chunk (<! ch)]
    (print (streaming/extract-content chunk))
    (recur)))
```

## Streaming Utilities

### extract-content

Extract text content from a stream chunk.

```clojure
(streaming/extract-content chunk)
;; => "partial text"
```

### consume-stream-with-callbacks

Higher-level API with callbacks for different events.

```clojure
(streaming/consume-stream-with-callbacks ch
  (fn [chunk]
    ;; Called for each chunk
    (print (streaming/extract-content chunk)))
  (fn [complete-response]
    ;; Called when stream completes
    (println "\nStream complete!"))
  (fn [error]
    ;; Called on error
    (println "Error:" error)))
```

### collect-stream

Collect all chunks into a single response.

```clojure
(let [complete-response (<! (streaming/collect-stream ch))]
  (println (core/extract-content complete-response)))
```

## Provider Support

| Provider     | Streaming Support |
|--------------|-------------------|
| OpenAI       | ✅ Yes           |
| Anthropic    | ✅ Yes           |
| Gemini       | ✅ Yes           |
| Mistral      | ✅ Yes           |
| OpenRouter   | ✅ Yes           |
| Ollama       | ✅ Yes           |

## Complete Examples

### CLI Application with Streaming

```clojure
(ns my-app.cli
  (:require [litellm.core :as core]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]))

(defn ask-streaming [question]
  (let [ch (core/completion :openai "gpt-4"
             {:messages [{:role :user :content question}]
              :stream true}
             {:api-key (System/getenv "OPENAI_API_KEY")})]
    
    (println "Assistant:")
    (streaming/consume-stream-with-callbacks ch
      (fn [chunk]
        (print (streaming/extract-content chunk))
        (flush))
      (fn [_] (println "\n"))
      (fn [error] (println "Error:" error)))))

(ask-streaming "Explain machine learning")
```

### Web Application with Server-Sent Events

```clojure
(ns my-app.web
  (:require [litellm.router :as router]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]))

(router/quick-setup!)

(defn stream-completion-handler [request]
  (let [question (get-in request [:params :question])
        ch (router/completion :openai
             {:messages [{:role :user :content question}]
              :stream true})]
    
    {:status 200
     :headers {"Content-Type" "text/event-stream"
               "Cache-Control" "no-cache"}
     :body (streaming/->sse-stream ch)}))
```

### React-style Progressive Rendering

```clojure
(ns my-app.ui
  (:require [litellm.core :as core]
            [litellm.streaming :as streaming]
            [clojure.core.async :refer [go-loop <!]]
            [reagent.core :as r]))

(defn streaming-chat []
  (let [response (r/atom "")]
    (fn []
      [:div
       [:button
        {:on-click
         (fn []
           (let [ch (core/completion :openai "gpt-4"
                      {:messages [{:role :user :content "Tell me a joke"}]
                       :stream true}
                      {:api-key "sk-..."})]
             (reset! response "")
             (go-loop []
               (when-let [chunk (<! ch)]
                 (when-let [content (streaming/extract-content chunk)]
                   (swap! response str content))
                 (recur)))))}
        "Get Joke"]
       [:div @response]])))
```

### Building Complete Response

```clojure
(require '[clojure.core.async :refer [<!]])

(defn get-complete-streaming-response [question]
  (let [ch (core/completion :openai "gpt-4"
             {:messages [{:role :user :content question}]
              :stream true}
             {:api-key "sk-..."})]
    ;; Collect all chunks into complete response
    (<! (streaming/collect-stream ch))))

;; Use in async context
(go
  (let [response (<! (get-complete-streaming-response "What is AI?"))]
    (println (core/extract-content response))))
```

### Streaming with Token Counting

```clojure
(defn stream-with-metrics [question]
  (let [ch (core/completion :openai "gpt-4"
             {:messages [{:role :user :content question}]
              :stream true}
             {:api-key "sk-..."})
        token-count (atom 0)
        start-time (System/currentTimeMillis)]
    
    (streaming/consume-stream-with-callbacks ch
      (fn [chunk]
        (when-let [content (streaming/extract-content chunk)]
          (swap! token-count + (core/estimate-tokens content))
          (print content)
          (flush)))
      (fn [_]
        (let [duration (- (System/currentTimeMillis) start-time)]
          (println (format "\n\nTokens: %d | Time: %dms | Tokens/sec: %.1f"
                          @token-count
                          duration
                          (/ (* @token-count 1000.0) duration)))))
      (fn [error]
        (println "Error:" error)))))
```

### Canceling Streams

```clojure
(require '[clojure.core.async :refer [close! timeout go <!]])

(defn stream-with-timeout [question max-time-ms]
  (let [ch (core/completion :openai "gpt-4"
             {:messages [{:role :user :content question}]
              :stream true}
             {:api-key "sk-..."})]
    
    (go
      (let [timeout-ch (timeout max-time-ms)]
        (loop []
          (let [[chunk port] (alts! [ch timeout-ch])]
            (cond
              (= port timeout-ch)
              (do
                (close! ch)
                (println "\nTimeout!"))
              
              chunk
              (do
                (print (streaming/extract-content chunk))
                (flush)
                (recur))
              
              :else
              (println "\nComplete!"))))))))
```

### Streaming with Multiple Providers

```clojure
(require '[litellm.router :as router])

(router/register! :fast {:provider :openai :model "gpt-4o-mini" :config {...}})
(router/register! :smart {:provider :anthropic :model "claude-3-opus-20240229" :config {...}})

(defn stream-from-multiple [question]
  (doseq [config [:fast :smart]]
    (println (str "\n=== " (name config) " ===\""))
    (let [ch (router/completion config
               {:messages [{:role :user :content question}]
                :stream true})]
      (<!! (streaming/consume-stream-with-callbacks ch
             (fn [chunk] (print (streaming/extract-content chunk)))
             (fn [_] (println))
             (fn [e] (println "Error:" e)))))))
```

## Error Handling

```clojure
(require '[litellm.errors :as errors])

(defn safe-stream [question]
  (try
    (let [ch (core/completion :openai "gpt-4"
               {:messages [{:role :user :content question}]
                :stream true}
               {:api-key "sk-..."})]
      
      (streaming/consume-stream-with-callbacks ch
        (fn [chunk] (print (streaming/extract-content chunk)))
        (fn [_] (println "\nSuccess!"))
        (fn [error]
          (if (errors/litellm-error? error)
            (println "LiteLLM Error:" (errors/error-summary error))
            (println "Unknown error:" error)))))
    
    (catch Exception e
      (println "Setup error:" (.getMessage e)))))
```

## Best Practices

### 1. Always Handle Stream Completion

```clojure
;; ❌ Don't do this - no completion handling
(go-loop []
  (when-let [chunk (<! ch)]
    (print (streaming/extract-content chunk))
    (recur)))

;; ✅ Do this - handle completion
(streaming/consume-stream-with-callbacks ch
  on-chunk
  on-complete
  on-error)
```

### 2. Flush Output for Real-time Display

```clojure
;; ✅ Flush after each print
(print (streaming/extract-content chunk))
(flush)
```

### 3. Handle Errors Gracefully

```clojure
;; ✅ Always provide error callback
(streaming/consume-stream-with-callbacks ch
  (fn [chunk] ...)
  (fn [response] ...)
  (fn [error]
    (log/error "Stream error" error)
    (notify-user "Something went wrong")))
```

### 4. Clean Up Resources

```clojure
;; ✅ Close channels when done
(when cancelled?
  (close! ch))
```

## Performance Tips

1. **Use streaming for long responses** - Better UX for responses > 100 tokens
2. **Buffer output** - Consider batching small chunks for UI updates
3. **Monitor token usage** - Track streaming costs in real-time
4. **Implement timeouts** - Prevent hanging connections

## Troubleshooting

### Stream Hangs

Check if the channel is being consumed:

```clojure
;; Make sure you're reading from the channel
(go-loop []
  (when-let [chunk (<! ch)]
    ;; Process chunk
    (recur)))
```

### Missing Content

Some chunks may have no content (metadata chunks):

```clojure
;; ✅ Check for nil
(when-let [content (streaming/extract-content chunk)]
  (print content))
```

### Memory Leaks

Close unused channels:

```clojure
;; Clean up when canceling
(close! ch)
```

## Next Steps

- Learn about [[error handling|/docs/ERROR_HANDLING.md]]
- Check out [[Core API|core-api]] for more details
- Browse [[examples|examples]] for more patterns
