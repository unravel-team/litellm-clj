# API Guide

LiteLLM Clojure provides two complementary APIs for interacting with LLM providers:

## Two API Styles

### Router API (`litellm.router`)

**Best for:** Applications with multiple models, configuration-based workflows, production deployments

The Router API uses named configurations to manage providers and models:

```clojure
(require '[litellm.router :as router])

;; Register configurations
(router/register! :fast {:provider :openai :model "gpt-4o-mini" :config {...}})
(router/register! :smart {:provider :anthropic :model "claude-3-opus-20240229" :config {...}})

;; Use by name
(router/completion :fast {:messages [...]})
(router/completion :smart {:messages [...]})
```

**Benefits:**
- ✅ Switch models by changing config name
- ✅ Centralized configuration management
- ✅ Easy to test with different models
- ✅ Clean separation of configuration and logic

### Core API (`litellm.core`)

**Best for:** Simple scripts, direct provider access, learning, prototyping

The Core API provides direct access to providers:

```clojure
(require '[litellm.core :as core])

;; Direct provider calls
(core/completion :openai "gpt-4o-mini" {...} {:api-key "sk-..."})
(core/completion :anthropic "claude-3-opus-20240229" {...} {:api-key "sk-ant-..."})
```

**Benefits:**
- ✅ No configuration needed
- ✅ Explicit provider and model selection
- ✅ Simpler for one-off scripts
- ✅ Direct control over all parameters

## Choosing the Right API

| Scenario | Recommended API |
|----------|----------------|
| Production application | Router API |
| Multiple models/providers | Router API |
| Configuration-driven workflow | Router API |
| Quick prototype/script | Core API |
| Learning LiteLLM | Core API |
| Testing different providers | Either (Router is easier) |

## Common Workflows

### Production Setup with Router

```clojure
(ns my-app.llm
  (:require [litellm.router :as router]))

;; On application startup
(defn init-llm! []
  (router/register! :default
    {:provider :openai
     :model "gpt-4o-mini"
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (router/register! :advanced
    {:provider :anthropic
     :model "claude-3-opus-20240229"
     :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}}))

;; In your application
(defn simple-query [text]
  (-> (router/completion :default {:messages [{:role :user :content text}]})
      router/extract-content))

(defn complex-query [text]
  (-> (router/completion :advanced {:messages [{:role :user :content text}]})
      router/extract-content))
```

### Script with Core API

```clojure
(ns my-script
  (:require [litellm.core :as llm]))

(defn analyze-text [text]
  (let [response (llm/completion :openai "gpt-4"
                   {:messages [{:role :user :content text}]}
                   {:api-key (System/getenv "OPENAI_API_KEY")})]
    (llm/extract-content response)))

(println (analyze-text "Summarize quantum computing"))
```

## API Reference

For detailed API documentation:

- [[Router API Guide|router-api]] - Configuration-based API
- [[Core API Guide|core-api]] - Direct provider API
- [[Streaming Guide|streaming]] - Streaming responses
- [[Examples|examples]] - Common patterns and recipes

## Provider Discovery

Both APIs provide provider discovery functions:

```clojure
;; List all available providers
(router/list-providers)
;; => [:openai :anthropic :gemini :mistral :ollama :openrouter]

;; Check if provider is available
(router/provider-available? :openai)
;; => true

;; Get provider capabilities
(router/provider-info :openai)
;; => {:streaming true :function-calling true ...}

;; Check specific capabilities
(router/supports-streaming? :anthropic)
;; => true

(router/supports-function-calling? :gemini)
;; => false
```

## Error Handling

Both APIs use the same error handling system. See [[Error Handling|/doc/error_handling.md]] for details.

## Next Steps

- Dive into the [[Router API|router-api]] reference
- Explore the [[Core API|core-api]] reference
- Learn about [[streaming|streaming]] responses
- Browse [[examples|examples]] for common patterns
