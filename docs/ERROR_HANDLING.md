# Error Handling Guide

This guide covers the comprehensive error handling strategy in litellm-clj.

## Overview

LiteLLM-clj uses a structured, namespaced error taxonomy that categorizes errors by their nature and provides rich context for debugging and retry logic.

## Error Categories

### 1. Client/Configuration Errors (4xx-style)

These errors indicate problems with the request or configuration that the user needs to fix.

| Error Type | Description | Recoverable? |
|------------|-------------|--------------|
| `:litellm/invalid-request` | Request validation failed | No |
| `:litellm/invalid-config` | Configuration validation failed | No |
| `:litellm/authentication-error` | API key invalid or missing | No |
| `:litellm/authorization-error` | API key valid but lacks permissions | No |
| `:litellm/provider-not-found` | Requested provider doesn't exist | No |
| `:litellm/model-not-found` | Model doesn't exist for provider | No |
| `:litellm/unsupported-feature` | Feature not supported by provider | No |
| `:litellm/quota-exceeded` | Account quota exhausted | No |

### 2. Provider/Network Errors (5xx-style)

These are transient errors that may succeed on retry.

| Error Type | Description | Recoverable? |
|------------|-------------|--------------|
| `:litellm/rate-limit` | Rate limit hit | Yes |
| `:litellm/timeout` | Request timeout | Yes |
| `:litellm/connection-error` | Network connectivity issues | Yes |
| `:litellm/server-error` | Provider's server error | Yes |
| `:litellm/provider-error` | Generic provider-side error | Maybe |

### 3. Response Errors

| Error Type | Description | Recoverable? |
|------------|-------------|--------------|
| `:litellm/invalid-response` | Response doesn't match schema | No |
| `:litellm/streaming-error` | Error during streaming | Maybe |
| `:litellm/content-filter` | Content filtered by safety | No |

### 4. System Errors

| Error Type | Description | Recoverable? |
|------------|-------------|--------------|
| `:litellm/internal-error` | Unexpected litellm bug | No |
| `:litellm/resource-exhausted` | Thread pool or buffer full | Yes |

## Error Data Structure

Every error includes standardized data:

```clojure
{:type :litellm/rate-limit           ; Namespaced keyword
 :message "Rate limit exceeded"      ; Human readable message
 :provider "openai"                  ; Provider name (when applicable)
 :http-status 429                    ; HTTP status code (when applicable)
 :provider-code "rate_limit_error"   ; Provider's error code
 :retry-after 60                     ; Seconds to wait (when applicable)
 :recoverable? true                  ; Whether retry might succeed
 :request-id "req-xyz"               ; Request ID for debugging
 :context {...}}                     ; Additional context
```

## Basic Error Handling

### Catching Specific Errors

```clojure
(require '[litellm.core :as llm]
         '[litellm.errors :as errors])

(try
  (llm/completion :openai "gpt-4" 
                  {:messages [{:role :user :content "Hello"}]
                   :api-key "invalid-key"})
  (catch clojure.lang.ExceptionInfo e
    (cond
      (errors/authentication-error? e)
      (println "Invalid API key!")
      
      (errors/rate-limit-error? e)
      (println "Rate limited, retry after:" 
               (:retry-after (ex-data e)))
      
      (errors/litellm-error? e)
      (println "LiteLLM error:" (errors/error-summary e))
      
      :else
      (println "Unexpected error:" (.getMessage e)))))
```

### Category-Based Handling

```clojure
(try
  (llm/completion :openai "gpt-4" request)
  (catch clojure.lang.ExceptionInfo e
    (let [category (errors/get-error-category e)]
      (case category
        :client-error (log/error "Fix your request:" (errors/error-details e))
        :provider-error (log/warn "Provider issue, retrying...")
        :response-error (log/error "Invalid response:" (errors/error-details e))
        :system-error (log/error "System error:" (errors/error-details e))
        (throw e)))))
```

## Retry Logic

### Simple Retry with Exponential Backoff

```clojure
(defn completion-with-retry [provider model request max-retries]
  (loop [attempt 0]
    (try
      (llm/completion provider model request)
      (catch clojure.lang.ExceptionInfo e
        (if (and (errors/should-retry? e 
                                       :max-retries max-retries 
                                       :current-retry attempt)
                 (< attempt max-retries))
          (let [delay (errors/retry-delay e attempt)]
            (log/info "Retrying after" delay "ms" 
                     {:error-type (:type (ex-data e))
                      :attempt (inc attempt)})
            (Thread/sleep delay)
            (recur (inc attempt)))
          (throw e))))))
```

### Advanced Retry with Timeout

```clojure
(defn completion-with-retry-timeout [provider model request opts]
  (let [{:keys [max-retries timeout-ms]} opts
        start-time (System/currentTimeMillis)]
    (loop [attempt 0]
      (let [elapsed (- (System/currentTimeMillis) start-time)]
        (when (> elapsed timeout-ms)
          (throw (errors/timeout-error 
                   (name provider)
                   "Overall operation timeout"
                   :timeout-ms timeout-ms)))
        
        (try
          (llm/completion provider model request)
          (catch clojure.lang.ExceptionInfo e
            (if (errors/should-retry? e 
                                      :max-retries max-retries 
                                      :current-retry attempt)
              (let [delay (errors/retry-delay e attempt)
                    remaining (- timeout-ms elapsed)]
                (if (> delay remaining)
                  (throw e)  ; Not enough time to retry
                  (do
                    (Thread/sleep delay)
                    (recur (inc attempt)))))
              (throw e))))))))
```

## Streaming Error Handling

Streaming requests handle errors differently because they return channels instead of throwing immediately.

### Synchronous Validation Errors

These are thrown before the channel is created:

```clojure
(try
  ;; This throws immediately if provider doesn't support streaming
  (llm/completion :some-provider "model" 
                  {:messages [...] :stream true})
  (catch clojure.lang.ExceptionInfo e
    (when (errors/unsupported-feature? e)
      (println "Provider doesn't support streaming"))))
```

### Asynchronous Streaming Errors

These are sent as error chunks on the channel:

```clojure
(require '[clojure.core.async :refer [<!!]])

(let [ch (llm/completion :openai "gpt-4" 
                         {:messages [...] :stream true :api-key "..."})]
  (loop []
    (when-let [chunk (<!! ch)]
      (if (streaming/is-error-chunk? chunk)
        ;; Handle error chunk
        (do
          (println "Streaming error:" (:message chunk))
          (println "Recoverable?" (:recoverable? chunk))
          (when (:recoverable? chunk)
            (println "Can retry this request")))
        ;; Process normal chunk
        (do
          (print (streaming/extract-content chunk))
          (recur))))))
```

### With Callbacks

```clojure
(streaming/consume-stream-with-callbacks ch
  ;; on-chunk
  (fn [chunk] (print (streaming/extract-content chunk)))
  
  ;; on-complete
  (fn [response] (println "\nDone!"))
  
  ;; on-error
  (fn [error-chunk]
    (log/error "Stream error:" (:message error-chunk))
    (when (:recoverable? error-chunk)
      ;; Implement retry logic
      (retry-streaming-request))))
```

## Error Analysis Utilities

### Get Error Details

```clojure
(try
  (llm/completion :openai "gpt-4" request)
  (catch clojure.lang.ExceptionInfo e
    (let [details (errors/error-details e)]
      (log/error "Error occurred:"
                 {:type (:error-type details)
                  :category (:category details)
                  :message (:message details)
                  :provider (:provider details)
                  :recoverable? (:recoverable? details)
                  :context (:context details)}))))
```

### Human-Readable Summary

```clojure
(try
  (llm/completion :openai "gpt-4" request)
  (catch clojure.lang.ExceptionInfo e
    (when (errors/litellm-error? e)
      (println (errors/error-summary e))
      ;; Example output: "Rate limit exceeded | Provider: openai | HTTP 429 | Recoverable | Retry after 60s"
      )))
```

## Provider-Specific Errors

Different providers may return different error codes. The library maps these to standard error types:

```clojure
;; OpenAI
401 -> :litellm/authentication-error
429 -> :litellm/rate-limit or :litellm/quota-exceeded
404 -> :litellm/model-not-found
500-504 -> :litellm/server-error

;; Anthropic
401 -> :litellm/authentication-error  
429 -> :litellm/rate-limit
403 -> :litellm/authorization-error

;; Gemini
400 -> :litellm/invalid-request
403 -> :litellm/authorization-error
```

The original provider error code is preserved in `:provider-code`:

```clojure
(try
  (llm/completion :openai "gpt-4" request)
  (catch clojure.lang.ExceptionInfo e
    (let [data (ex-data e)]
      (println "Provider code:" (:provider-code data))
      ;; e.g., "rate_limit_exceeded", "insufficient_quota", etc.
      )))
```

## Best Practices

### 1. Always Check Recoverability

```clojure
(catch clojure.lang.ExceptionInfo e
  (if (errors/recoverable? e)
    (retry-with-backoff)
    (alert-and-fail)))
```

### 2. Log Request IDs

```clojure
(catch clojure.lang.ExceptionInfo e
  (log/error "Request failed"
             {:request-id (:request-id (ex-data e))
              :error (errors/error-summary e)}))
```

### 3. Differentiate Client vs Provider Errors

```clojure
(catch clojure.lang.ExceptionInfo e
  (if (errors/client-error? e)
    ;; User's fault - don't retry, fix the request
    (show-validation-errors)
    ;; Provider's fault - might succeed on retry
    (retry-or-fallback)))
```

### 4. Handle Rate Limits Gracefully

```clojure
(catch clojure.lang.ExceptionInfo e
  (when (errors/rate-limit-error? e)
    (let [retry-after (:retry-after (ex-data e))]
      (if retry-after
        (do
          (log/info "Rate limited, waiting" retry-after "seconds")
          (Thread/sleep (* 1000 retry-after))
          (retry))
        ;; No retry-after header, use exponential backoff
        (exponential-backoff-retry)))))
```

### 5. Preserve Error Context

```clojure
;; Don't just log the message
(catch Exception e
  (log/error (.getMessage e)))  ; BAD

;; Log the full context
(catch clojure.lang.ExceptionInfo e
  (log/error "Completion failed" (errors/error-details e)))  ; GOOD
```

## Creating Custom Errors

For library extensions or custom providers:

```clojure
(require '[litellm.errors :as errors])

;; Throw a proper error
(throw (errors/provider-error
         "my-provider"
         "Custom error occurred"
         :http-status 503
         :provider-code "custom_error"
         :recoverable? true))

;; Create streaming error chunks
(>! channel (errors/streaming-error-chunk
              "my-provider"
              "Stream interrupted"
              :error-type :litellm/connection-error
              :recoverable? true))
```

## Testing Error Handling

```clojure
(require '[clojure.test :refer :all]
         '[litellm.errors :as errors])

(deftest test-rate-limit-retry
  (testing "Rate limit triggers retry"
    (let [error (errors/rate-limit "openai" "Rate limited" :retry-after 60)]
      (is (errors/rate-limit-error? error))
      (is (errors/recoverable? error))
      (is (errors/should-retry? error :max-retries 3 :current-retry 0))
      (is (= 60000 (errors/retry-delay error 0))))))
```

## Migration from Old Error Format

If you have existing code using the old error format:

```clojure
;; Old format (still works but deprecated)
(catch clojure.lang.ExceptionInfo e
  (case (:type (ex-data e))
    :authentication-error ...
    :rate-limit-error ...))

;; New format (recommended)
(catch clojure.lang.ExceptionInfo e
  (cond
    (errors/authentication-error? e) ...
    (errors/rate-limit-error? e) ...))
```

The library maintains backward compatibility through wrapper functions in `litellm.providers.core`.
