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
| DeepSeek     | ✅ Yes           | deepseek-v4-flash, deepseek-v4-pro |
| Kimi/Moonshot| ✅ Yes           | kimi-k2.7-code, kimi-k2.6, kimi-k2.5 |
| Z.AI GLM     | ✅ Yes           | glm-5.2, glm-5.1, glm-4.7, glm-4.5 |

**Note**: Provider-native reasoning fields differ. `litellm-clj` normalizes common response extraction to `:reasoning-content` and preserves provider-specific request controls.

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
:reasoning-effort :minimal ; disable or minimize where supported
:reasoning-effort :none    ; disable where supported
:reasoning-effort :low     ; provider-specific low/default effort
:reasoning-effort :medium  ; provider-specific medium effort
:reasoning-effort :high    ; high effort
:reasoning-effort :xhigh   ; extra-high effort where supported
:reasoning-effort :max     ; maximum effort where supported
```

**When to use:**
- `:minimal` / `:none` - Disable or minimize thinking where provider supports it
- `:low` - Simple questions, quick answers
- `:medium` - Moderate complexity problems
- `:high` - Complex reasoning, multi-step problems
- `:xhigh` / `:max` - Provider-specific maximum effort

### thinking

Provides explicit control over thinking configuration:

```clojure
:thinking {:type :enabled
          :budget-tokens 5000
          :keep :all
          :clear-thinking false}
```

**Note**: `thinking` takes precedence over `reasoning-effort` when both are provided. DeepSeek accepts `thinking.type`; Kimi accepts `thinking.type` and `thinking.keep`; Z.AI accepts `thinking.type` and `thinking.clear-thinking`. Kimi K2.7 Code cannot disable thinking.

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

## Provider Notes

- **DeepSeek**: `:reasoning-effort :low` and `:medium` map to provider `high`; `:xhigh` and `:max` map to provider `max`; `:minimal` and `:none` disable thinking when no explicit `:thinking` is supplied.
- **Kimi/Moonshot**: `:max-tokens` is sent as `max_completion_tokens`. K2.7 Code has thinking always on; disabling it raises `:litellm/invalid-request` before HTTP.
- **Z.AI GLM**: supports `:reasoning-effort` values through `:max`, plus `:thinking {:clear-thinking false}` or top-level `:clear-thinking false` for preserved thinking. `:tool-choice` currently supports only `:auto`.

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

Reasoning support is now available for Anthropic, OpenAI, DeepSeek, Kimi/Moonshot, and Z.AI GLM. Additional providers such as XAI can be added behind the same `:reasoning-content` response shape as provider APIs mature.

## API Reference

### Request Parameters

```clojure
:reasoning-effort  ; :minimal | :none | :low | :medium | :high | :xhigh | :max
:thinking          ; {:type :enabled|:disabled :budget-tokens <int> :keep :all :clear-thinking false}
:clear-thinking    ; Z.AI convenience field; nested :thinking wins
```

### Response Fields

```clojure
:reasoning-content   ; String - reasoning text
:thinking-blocks     ; Vector - detailed thinking blocks (Anthropic)
```

## Next Steps

- Try the [reasoning example](../examples/09_reasoning_example.clj)
- See provider examples: [DeepSeek](../examples/10_deepseek_example.clj), [Kimi](../examples/11_kimi_example.clj), [Z.AI GLM](../examples/12_zai_glm_example.clj)
- Learn about [streaming](streaming.md) reasoning content
- Explore [error handling](error_handling.md)
- Check [API guide](api-guide.md) for more details

## Related Documentation

- [Core API Reference](core-api.md)
- [Router API Reference](router-api.md)
- [Streaming Guide](streaming.md)
- [Examples](examples.md)
