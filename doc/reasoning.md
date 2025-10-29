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
| OpenAI       | 🔄 Coming Soon   | o1, o1-mini, o1-preview |
| Deepseek     | 🔄 Coming Soon   | deepseek-chat |
| XAI          | 🔄 Coming Soon   | grok models |

**Note**: Currently only Anthropic provider is fully implemented. Support for other providers will be added in future releases.

## Quick Start

### Basic Reasoning with reasoning-effort

The simplest way to enable reasoning is using the `reasoning-effort` parameter:

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

## Complete Examples

### Example 1: Problem Solving

```clojure
(defn solve-with-reasoning [problem]
  (let [response (core/completion :anthropic "claude-3-7-sonnet-20250219"
                   {:messages [{:role :user :content problem}]
                    :reasoning-effort :medium}
                   {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    
    {:answer (get-in response [:choices 0 :message :content])
     :reasoning (get-in response [:choices 0 :message :reasoning-content])
     :usage (get-in response [:usage])}))

(def result (solve-with-reasoning 
              "What is 15% of 80, plus 20?"))

(println "Reasoning:" (:reasoning result))
;; => "Let me break this down: First, 15% of 80 is 0.15 * 80 = 12..."

(println "Answer:" (:answer result))
;; => "The answer is 32."
```

### Example 2: Reasoning with Tool Calling

```clojure
(defn get-weather [location]
  "{\"temperature\": 72, \"condition\": \"sunny\"}")

(let [tools [{:type "function"
              :function {:name "get_weather"
                        :description "Get weather for location"
                        :parameters {:type "object"
                                    :properties {:location {:type "string"}}}}}]
      
      ;; First request with reasoning
      response1 (core/completion :anthropic "claude-3-7-sonnet-20250219"
                  {:messages [{:role :user 
                              :content "What's the weather in Paris?"}]
                   :tools tools
                   :reasoning-effort :medium}
                  {:api-key "sk-ant-..."})]
  
  ;; Model's reasoning about which tool to call
  (println "Reasoning:" 
           (get-in response1 [:choices 0 :message :reasoning-content]))
  
  ;; Execute tool call
  (let [tool-call (first (get-in response1 [:choices 0 :message :tool-calls]))
        result (get-weather "Paris")
        
        ;; Second request with tool result
        response2 (core/completion :anthropic "claude-3-7-sonnet-20250219"
                    {:messages [{:role :user :content "What's the weather in Paris?"}
                               (get-in response1 [:choices 0 :message])
                               {:role :tool
                                :tool-call-id (:id tool-call)
                                :content result}]
                     :reasoning-effort :medium}
                    {:api-key "sk-ant-..."})]
    
    (println "Final answer:" 
             (get-in response2 [:choices 0 :message :content]))))
```

### Example 3: Comparing Reasoning Levels

```clojure
(defn compare-reasoning-efforts [question]
  (doseq [effort [:low :medium :high]]
    (let [response (core/completion :anthropic "claude-3-7-sonnet-20250219"
                     {:messages [{:role :user :content question}]
                      :reasoning-effort effort
                      :max-tokens 500}
                     {:api-key "sk-ant-..."})]
      
      (println (format "\n=== Effort: %s ===" (name effort)))
      (println "Tokens:" (get-in response [:usage :total-tokens]))
      (println "Reasoning length:" 
               (count (get-in response [:choices 0 :message :reasoning-content]))))))

(compare-reasoning-efforts 
  "What are the implications of AI on society?")
```

### Example 4: Progressive Streaming Display

```clojure
(require '[litellm.streaming :as streaming])

(defn stream-with-reasoning [question]
  (let [ch (core/completion :anthropic "claude-3-7-sonnet-20250219"
             {:messages [{:role :user :content question}]
              :reasoning-effort :high
              :stream true}
             {:api-key "sk-ant-..."})]
    
    (println "🤔 Thinking...")
    (streaming/consume-stream-with-callbacks ch
      (fn [chunk]
        ;; Show reasoning in gray
        (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning-content])]
          (print "\033[90m" reasoning "\033[0m"))
        ;; Show answer in normal color
        (when-let [content (get-in chunk [:choices 0 :delta :content])]
          (print content)
          (flush)))
      (fn [_] (println "\n✓ Complete"))
      (fn [error] (println "Error:" error)))))

(stream-with-reasoning "Explain the theory of relativity")
```

## Token Usage and Costs

Reasoning tokens count toward your total token usage:

```clojure
(let [response (core/completion :anthropic "claude-3-7-sonnet-20250219"
                 {:messages [{:role :user :content "Complex problem"}]
                  :reasoning-effort :high}
                 {:api-key "sk-ant-..."})]
  
  (let [usage (get-in response [:usage])]
    (println "Prompt tokens:" (:prompt-tokens usage))
    (println "Completion tokens:" (:completion-tokens usage))
    (println "Total tokens:" (:total-tokens usage))
    
    ;; Estimate cost
    (let [cost (core/calculate-cost :anthropic 
                                    "claude-3-7-sonnet-20250219"
                                    (:prompt-tokens usage)
                                    (:completion-tokens usage))]
      (println "Estimated cost:" cost))))
```

**Cost Considerations:**
- Higher reasoning effort = more tokens = higher cost
- Balance reasoning quality with cost requirements
- Use `:low` for simple tasks, `:high` only when needed

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
