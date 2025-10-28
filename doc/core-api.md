# Core API Reference

The `litellm.core` namespace provides direct access to LLM providers without configuration management.

## Overview

```clojure
(require '[litellm.core :as core])
```

**Best for:**
- Simple scripts and prototypes
- Direct provider control
- Learning and experimentation
- One-off tasks

## Main Functions

### completion

The primary function for making LLM requests.

```clojure
(core/completion provider model request-map)
(core/completion provider model request-map config)
```

**Parameters:**
- `provider` - Provider keyword (`:openai`, `:anthropic`, `:gemini`, `:mistral`, `:ollama`, `:openrouter`)
- `model` - Model name string (e.g., `"gpt-4"`, `"claude-3-opus-20240229"`)
- `request-map` - Request parameters including `:messages`, `:temperature`, etc.
- `config` - Optional config map with `:api-key`, `:api-base`, `:timeout`

**Returns:**
- Non-streaming: Response map with `:choices`, `:usage`, etc.
- Streaming: `core.async` channel

**Examples:**

```clojure
;; Basic completion
(def response
  (core/completion :openai "gpt-4o-mini"
    {:messages [{:role :user :content "Hello!"}]}
    {:api-key "sk-..."}))

;; With temperature
(def response
  (core/completion :anthropic "claude-3-sonnet-20240229"
    {:messages [{:role :user :content "Write a poem"}]
     :temperature 0.9}
    {:api-key "sk-ant-..."}))

;; Streaming
(def ch
  (core/completion :openai "gpt-4"
    {:messages [{:role :user :content "Tell me a story"}]
     :stream true}
    {:api-key "sk-..."}))
```

### chat

Simplified chat interface for single messages.

```clojure
(core/chat provider model message & {:keys [system-prompt] :as config})
```

**Examples:**

```clojure
;; Simple question
(core/chat :openai "gpt-4o-mini" "What is 2+2?"
  :api-key "sk-...")

;; With system prompt
(core/chat :anthropic "claude-3-sonnet-20240229"
  "Explain quantum physics"
  :system-prompt "You are a physics professor"
  :api-key "sk-ant-...")
```

## Provider-Specific Functions

Convenience functions for each provider:

```clojure
;; OpenAI
(core/openai-completion "gpt-4" {...} :api-key "sk-...")

;; Anthropic
(core/anthropic-completion "claude-3-opus-20240229" {...} :api-key "sk-ant-...")

;; Gemini
(core/gemini-completion "gemini-pro" {...} :api-key "...")

;; Mistral
(core/mistral-completion "mistral-medium" {...} :api-key "...")

;; Ollama
(core/ollama-completion "llama3" {...} :api-base "http://localhost:11434")

;; OpenRouter
(core/openrouter-completion "openai/gpt-4" {...} :api-key "sk-or-...")
```

## Response Utilities

### extract-content

Extract text content from response.

```clojure
(core/extract-content response)
;; => "The content of the response..."
```

### extract-message

Extract the full message object.

```clojure
(core/extract-message response)
;; => {:role :assistant :content "..." :tool-calls [...]}
```

### extract-usage

Get token usage information.

```clojure
(core/extract-usage response)
;; => {:prompt-tokens 10 :completion-tokens 20 :total-tokens 30}
```

## Provider Discovery

### list-providers

List all available providers.

```clojure
(core/list-providers)
;; => [:openai :anthropic :gemini :mistral :ollama :openrouter]
```

### provider-available?

Check if a provider is registered.

```clojure
(core/provider-available? :openai)
;; => true
```

### provider-info

Get provider capabilities and status.

```clojure
(core/provider-info :openai)
;; => {:name :openai
;;     :streaming true
;;     :function-calling true
;;     :vision false}
```

### supports-streaming?

Check streaming support.

```clojure
(core/supports-streaming? :anthropic)
;; => true
```

### supports-function-calling?

Check function calling support.

```clojure
(core/supports-function-calling? :gemini)
;; => false
```

## Validation

### validate-request

Validate a request before sending.

```clojure
(core/validate-request :openai {:messages [...]})
;; Throws exception if invalid
```

## Cost Estimation

### estimate-tokens

Estimate token count for text.

```clojure
(core/estimate-tokens "Hello, world!")
;; => 4
```

### estimate-request-tokens

Estimate tokens for a full request.

```clojure
(core/estimate-request-tokens {:messages [{:role :user :content "Hi"}]})
;; => {:prompt-tokens 5 :estimated-completion-tokens 100}
```

### calculate-cost

Calculate estimated cost.

```clojure
(core/calculate-cost :openai "gpt-4" 1000 500)
;; => {:prompt-cost 0.03 :completion-cost 0.06 :total-cost 0.09}
```

## Advanced Examples

### Multi-turn Conversation

```clojure
(def conversation
  [{:role :system :content "You are a helpful assistant"}
   {:role :user :content "What's the capital of France?"}
   {:role :assistant :content "Paris"}
   {:role :user :content "What's its population?"}])

(def response
  (core/completion :openai "gpt-4"
    {:messages conversation}
    {:api-key "sk-..."}))
```

### Function Calling (OpenAI)

```clojure
(def response
  (core/completion :openai "gpt-4"
    {:messages [{:role :user :content "What's the weather in Boston?"}]
     :tools [{:type "function"
              :function {:name "get_weather"
                        :description "Get current weather"
                        :parameters {:type "object"
                                    :properties {:location {:type "string"}}
                                    :required ["location"]}}}]}
    {:api-key "sk-..."}))

;; Check for tool calls
(when-let [tool-calls (-> response core/extract-message :tool-calls)]
  (doseq [call tool-calls]
    (println "Function:" (get-in call [:function :name]))
    (println "Args:" (get-in call [:function :arguments]))))
```

### Error Handling

```clojure
(require '[litellm.errors :as errors])

(try
  (core/completion :openai "gpt-4"
    {:messages [{:role :user :content "Hello"}]}
    {:api-key "invalid"})
  (catch Exception e
    (if (errors/litellm-error? e)
      (do
        (println "Category:" (errors/get-error-category e))
        (println "Summary:" (errors/error-summary e)))
      (throw e))))
```

## Next Steps

- Learn about [[streaming responses|streaming]]
- Explore the [[Router API|router-api]] for configuration management
- Check out [[examples|examples]] for common patterns
- Read about [[error handling|/docs/ERROR_HANDLING.md]]
