# Router API Reference

The `litellm.router` namespace provides a configuration-based API for managing multiple LLM providers and models.

## Overview

```clojure
(require '[litellm.router :as router])
```

**Best for:**
- Production applications
- Managing multiple models/providers
- Configuration-driven workflows
- Easy model switching

## Core Concepts

The Router API separates **configuration** from **usage**:

1. **Register** configurations with names
2. **Use** configurations by name in your code
3. **Switch** models by changing configuration only

## Configuration Management

### register!

Register a provider configuration with a keyword name.

```clojure
(router/register! config-name config-map)
```

**Simple Configuration:**

```clojure
(router/register! :fast
  {:provider :openai
   :model "gpt-4o-mini"
   :config {:api-key "sk-..."}})
```

**With Router Function:**

```clojure
(router/register! :adaptive
  {:router (fn [{:keys [priority]}]
             (if (= priority :high)
               {:provider :anthropic :model "claude-3-opus-20240229"}
               {:provider :openai :model "gpt-4o-mini"}))
   :configs {:openai {:api-key "sk-..."}
             :anthropic {:api-key "sk-ant-..."}}})
```

### unregister!

Remove a configuration.

```clojure
(router/unregister! :fast)
```

### list-configs

List all registered configuration names.

```clojure
(router/list-configs)
;; => [:fast :smart :adaptive]
```

### get-config

Retrieve a configuration.

```clojure
(router/get-config :fast)
;; => {:provider :openai :model "gpt-4o-mini" :config {...}}
```

### clear-router!

Clear all configurations (useful for testing).

```clojure
(router/clear-router!)
```

## Making Requests

### completion

Main completion function using registered configurations.

```clojure
(router/completion config-name request-map)
```

**Examples:**

```clojure
;; Basic usage
(def response
  (router/completion :fast
    {:messages [{:role :user :content "Hello!"}]}))

;; With options
(def response
  (router/completion :smart
    {:messages [{:role :user :content "Explain quantum computing"}]
     :temperature 0.7
     :max-tokens 500}))

;; Streaming
(def ch
  (router/completion :fast
    {:messages [{:role :user :content "Tell a story"}]
     :stream true}))
```

### chat

Simplified chat interface.

```clojure
(router/chat config-name message & {:keys [system-prompt]})
```

**Examples:**

```clojure
;; Simple question
(router/chat :fast "What is 2+2?")

;; With system prompt
(router/chat :smart
  "Explain general relativity"
  :system-prompt "You are a physics professor")
```

## Quick Setup Functions

### quick-setup!

Auto-configure from environment variables.

```clojure
(router/quick-setup!)
```

Sets up configurations for providers with available API keys:
- `:openai` if `OPENAI_API_KEY` is set
- `:anthropic` if `ANTHROPIC_API_KEY` is set
- `:gemini` if `GEMINI_API_KEY` is set
- `:mistral` if `MISTRAL_API_KEY` is set
- `:openrouter` if `OPENROUTER_API_KEY` is set
- `:ollama` (always, defaults to localhost)

### setup-openai!

Quick setup for OpenAI.

```clojure
(router/setup-openai! & {:keys [config-name api-key model]})
```

**Examples:**

```clojure
;; Use defaults (config-name :openai, model "gpt-4o-mini")
(router/setup-openai!)

;; Custom configuration
(router/setup-openai!
  :config-name :gpt4
  :model "gpt-4"
  :api-key "sk-...")
```

### setup-anthropic!

Quick setup for Anthropic.

```clojure
(router/setup-anthropic! & {:keys [config-name api-key model]})
```

**Examples:**

```clojure
;; Defaults: config-name :anthropic, model "claude-3-sonnet-20240229"
(router/setup-anthropic!)

;; Custom
(router/setup-anthropic!
  :config-name :claude-opus
  :model "claude-3-opus-20240229")
```

### setup-gemini!

Quick setup for Google Gemini.

```clojure
(router/setup-gemini! & {:keys [config-name api-key model]})
```

### setup-mistral!

Quick setup for Mistral.

```clojure
(router/setup-mistral! & {:keys [config-name api-key model]})
```

### setup-ollama!

Quick setup for Ollama (local models).

```clojure
(router/setup-ollama! & {:keys [config-name api-base model]})
```

**Example:**

```clojure
(router/setup-ollama!
  :config-name :local
  :model "llama3"
  :api-base "http://localhost:11434")
```

### setup-openrouter!

Quick setup for OpenRouter.

```clojure
(router/setup-openrouter! & {:keys [config-name api-key model]})
```

## Advanced Routing

### create-router

Create a dynamic router configuration.

```clojure
(router/create-router router-fn provider-configs)
```

**Example:**

```clojure
(def adaptive-router
  (router/create-router
    (fn [request]
      (let [complexity (get-in request [:metadata :complexity])]
        (case complexity
          :high {:provider :anthropic :model "claude-3-opus-20240229"}
          :medium {:provider :openai :model "gpt-4"}
          :low {:provider :openai :model "gpt-4o-mini"})))
    {:openai {:api-key "sk-..."}
     :anthropic {:api-key "sk-ant-..."}}))

(router/register! :adaptive adaptive-router)

;; Use with metadata
(router/completion :adaptive
  {:messages [{:role :user :content "Complex task"}]
   :metadata {:complexity :high}})
```

## Response Utilities

These are re-exported from [[litellm.core]]:

```clojure
;; Extract content
(router/extract-content response)

;; Extract full message
(router/extract-message response)

;; Get usage stats
(router/extract-usage response)
```

## Provider Discovery

Re-exported from [[litellm.core]]:

```clojure
;; List providers
(router/list-providers)

;; Check availability
(router/provider-available? :openai)

;; Get info
(router/provider-info :openai)

;; Check capabilities
(router/supports-streaming? :anthropic)
(router/supports-function-calling? :openai)
```

## Validation

```clojure
;; Validate request
(router/validate-request :openai {...})
```

## Next Steps

- Learn about [[streaming|streaming]] responses
- Explore the [[Core API|core-api]] for direct access
- Check out [[examples|examples]] for common patterns
- Read about [[error handling|/docs/ERROR_HANDLING.md]]
