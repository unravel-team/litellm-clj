# Reasoning/Thinking Content Guide

LiteLLM Clojure provides support for sending and receiving reasoning tokens from LLM providers that support extended thinking capabilities. This allows models to show their step-by-step reasoning process before providing a final answer.

## Overview

**Reasoning/thinking content** enables you to:
- ✅ Access the model's internal reasoning process
- ✅ Get step-by-step explanations for complex problems
- ✅ Control thinking token budgets
- ✅ Understand how the model arrived at its answer
- ✅ Improve transparency and trust in AI responses

## Supported Providers

| Provider     | Reasoning Support | Models |
|--------------|-------------------|--------|
| Anthropic    | ✅ Yes           | Claude 3.7 Sonnet |
| OpenAI       | ✅ Yes           | o1, o1-mini, o1-preview |
| Deepseek     | 🔄 Coming Soon   | deepseek-chat |
| XAI          | 🔄 Coming Soon   | grok models |

**Note**: Anthropic and OpenAI providers are now fully implemented. Support for other providers will be added in future releases.

## Quick Start

### Basic Reasoning with reasoning-effort

The simplest way to enable reasoning is using the `reasoning-effort` parameter:

**Anthropic Example:**
```clojure
(require '[litellm.core :as core])

(def response
  (core/completion :anthropic "claude-3-7-sonnet-20250219"
    {:messages [{:role :user :content "What is the capital of France?"}]
     :reasoning-effort :low}
    {:api-key "sk-ant-..."}))

;; Access reasoning content
(get-in response [:choices 0 :message :reasoning-content])
;; => "Let me think about this. France is a country in Europe..."

;; Access the actual answer
(get-in response [:choices 0 :message :content])
;; => "The capital of France is Paris."
```

**OpenAI Example:**
```clojure
(def response
  (core/completion :openai "o1"
    {:messages [{:role :user :content "What is the capital of France?"}]
     :reasoning-effort :medium}
    {:api-key "sk-..."}))

;; Access reasoning content
(get-in response [:choices 0 :message :reasoning-content])
;; => "To answer this question, I need to recall..."

;; Access the actual answer
(get-in response [:choices 0 :message :content])
;; => "The capital of France is Paris."
```

### Advanced Reasoning with thinking config

For more control, use the `thinking` parameter:

```clojure
(def response
  (core/completion :anthropic "claude-3-7-sonnet-20250219"
    {:messages [{:role :user 
                :content "Solve: If a train travels 120 km in 2 hours, what is its average speed?"}]
     :thinking {:type :enabled
               :budget-tokens 2048}}
    {:api-key "sk-ant-..."}))

;; Access detailed thinking blocks (Anthropic-specific)
(get-in response [:choices 0 :message :thinking-blocks])
;; => [{:type "thinking"
;;      :thinking "Step 1: I need to calculate average speed..."
;;      :signature "EqoBCkgIARABGAIiQL2U..."}]
```

## Parameters

### reasoning-effort

Controls the level of reasoning the model should apply:

```clojure
:reasoning-effort :low     ; ~1024 tokens for thinking
:reasoning-effort :medium  ; ~4096 tokens for thinking
:reasoning-effort :high    ; ~10000 tokens for thinking
```

**When to use:**
- `:low` - Simple questions, quick answers
- `:medium` - Moderate complexity problems
- `:high` - Complex reasoning, multi-step problems

### thinking

Provides explicit control over thinking configuration:

```clojure
:thinking {:type :enabled
          :budget-tokens 5000}
```

**Note**: `thinking` takes precedence over `reasoning-effort` when both are provided.

## Response Structure

### reasoning-content

The text-based reasoning process:

```clojure
(get-in response [:choices 0 :message :reasoning-content])
;; => "First, I'll identify the key information..."
```

### thinking-blocks (Anthropic-specific)

Detailed thinking blocks with cryptographic signatures:

```clojure
(get-in response [:choices 0 :message :thinking-blocks])
;; => [{:type "thinking"
;;      :thinking "Step-by-step reasoning..."
;;      :signature "EqoBCkgIARABGAIiQ..."}]
```

The signature provides cryptographic verification that the thinking came from the model.

## Streaming with Reasoning

Reasoning content can be streamed for progressive rendering:

```clojure
(require '[litellm.streaming :as streaming]
         '[clojure.core.async :refer [go-loop <!]])

(let [ch (core/completion :anthropic "claude-3-7-sonnet-20250219"
           {:messages [{:role :user :content "Explain quantum physics"}]
            :reasoning-effort :high
            :stream true}
           {:api-key "sk-ant-..."})]
  
  (go-loop []
    (when-let [chunk (<! ch)]
      ;; Regular content
      (when-let [content (get-in chunk [:choices 0 :delta :content])]
        (print content)
        (flush))
      
      ;; Reasoning content chunks
      (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning-content])]
        (println "\n[Thinking]:" reasoning))
      
      (recur))))
```


## Best Practices

### 1. Choose Appropriate Reasoning Level

```clojure
;; ❌ Don't use high reasoning for simple tasks
(core/completion :anthropic "claude-3-7-sonnet-20250219"
  {:messages [{:role :user :content "What is 2+2?"}]
   :reasoning-effort :high})  ; Wasteful

;; ✅ Match effort to complexity
(core/completion :anthropic "claude-3-7-sonnet-20250219"
  {:messages [{:role :user :content "What is 2+2?"}]
   :reasoning-effort :low})  ; Appropriate
```

### 2. Use Streaming for Long Reasoning

```clojure
;; ✅ Stream for better UX
(core/completion :anthropic "claude-3-7-sonnet-20250219"
  {:messages [{:role :user :content "Complex analysis"}]
   :reasoning-effort :high
   :stream true})
```

### 3. Handle Missing Reasoning Gracefully

```clojure
;; ✅ Check for presence
(when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
  (display-reasoning reasoning))
```

### 4. Store Thinking Blocks for Auditing

```clojure
;; ✅ Preserve thinking blocks with signatures
(let [response (core/completion ...)]
  (doseq [block (get-in response [:choices 0 :message :thinking-blocks])]
    (store-audit-log {:thinking (:thinking block)
                     :signature (:signature block)
                     :timestamp (System/currentTimeMillis)})))
```

## Router API Support

Reasoning works with the Router API:

```clojure
(require '[litellm.router :as router])

(router/register! :reasoning
  {:provider :anthropic
   :model "claude-3-7-sonnet-20250219"
   :config {:api-key "sk-ant-..."}})

(def response
  (router/completion :reasoning
    {:messages [{:role :user :content "Explain..."}]
     :reasoning-effort :medium}))
```

## Troubleshooting

### No Reasoning Content in Response

**Possible causes:**
1. Model doesn't support reasoning
2. Request too simple (model skipped reasoning)
3. Budget tokens too low

**Solution:**
```clojure
;; Ensure sufficient budget
:thinking {:type :enabled
          :budget-tokens 4096}  ; Increase if needed
```

### Reasoning Truncated

**Cause:** Budget too low for problem complexity

**Solution:**
```clojure
;; Increase budget
:reasoning-effort :high  ; or
:thinking {:type :enabled :budget-tokens 10000}
```

### High Token Usage

**Cause:** Reasoning effort too high for task

**Solution:**
```clojure
;; Reduce effort level
:reasoning-effort :low  ; Start low, increase if needed
```

## Future Provider Support

Support for additional providers is planned:

- **OpenAI**: o1, o1-mini, o1-preview models
- **Deepseek**: deepseek-chat with reasoning
- **XAI**: Grok models
- **Others**: As providers add reasoning capabilities

The API design is provider-agnostic, so adding new providers will not require code changes in your application.

## API Reference

### Request Parameters

```clojure
:reasoning-effort  ; :low | :medium | :high
:thinking         ; {:type :enabled :budget-tokens <int>}
```

### Response Fields

```clojure
:reasoning-content   ; String - reasoning text
:thinking-blocks     ; Vector - detailed thinking blocks (Anthropic)
```

## Next Steps

- Try the [reasoning example](../examples/08_reasoning_example.clj)
- Learn about [streaming](streaming.md) reasoning content
- Explore [error handling](error_handling.md)
- Check [API guide](api-guide.md) for more details

## Related Documentation

- [Core API Reference](core-api.md)
- [Router API Reference](router-api.md)
- [Streaming Guide](streaming.md)
- [Examples](examples.md)
