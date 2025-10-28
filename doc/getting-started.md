# Getting Started with LiteLLM Clojure

Welcome to LiteLLM Clojure! This guide will help you get up and running quickly.

## Prerequisites

- Clojure 1.11.1 or higher
- Java 11 or higher
- API keys for the providers you want to use

## Installation

### Using deps.edn

Add to your `deps.edn`:

```clojure
{:deps {tech.unravel/litellm-clj {:mvn/version "0.2.0"}}}
```

### Using Leiningen

Add to your `project.clj`:

```clojure
[tech.unravel/litellm-clj "0.2.0"]
```

## Quick Setup

LiteLLM provides two API styles: **Router API** (recommended for most use cases) and **Core API** (for direct provider calls).

### Option 1: Router API (Recommended)

The Router API uses named configurations, making it easy to switch between models:

```clojure
(require '[litellm.router :as router])

;; Quick setup from environment variables
(router/quick-setup!)

;; Or register specific configurations
(router/register! :fast
  {:provider :openai
   :model "gpt-4o-mini"
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Use the configuration
(def response (router/completion :fast
                {:messages [{:role :user :content "Hello!"}]}))

(println (router/extract-content response))
;; => "Hello! How can I assist you today?"
```

### Option 2: Core API

The Core API provides direct access to providers:

```clojure
(require '[litellm.core :as core])

;; Direct provider call
(def response (core/completion :openai "gpt-4o-mini"
                {:messages [{:role :user :content "Hello!"}]
                 :api-key (System/getenv "OPENAI_API_KEY")}))

(println (core/extract-content response))
;; => "Hello! How can I assist you today?"
```

## Setting Up API Keys

### Environment Variables (Recommended)

Set environment variables for your providers:

```bash
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."
export GEMINI_API_KEY="..."
export MISTRAL_API_KEY="..."
export OPENROUTER_API_KEY="sk-or-..."
```

Then use `quick-setup!`:

```clojure
(router/quick-setup!)
;; Automatically sets up all providers with available API keys
```

### Manual Configuration

```clojure
(router/setup-openai! :api-key "sk-..." :model "gpt-4")
(router/setup-anthropic! :api-key "sk-ant-..." :model "claude-3-sonnet-20240229")
(router/setup-gemini! :api-key "..." :model "gemini-pro")
```

## Your First Request

### Simple Chat

```clojure
(require '[litellm.router :as router])

(router/quick-setup!)

;; Simple question
(def response (router/chat :openai "What is the capital of France?"))
(println (router/extract-content response))
;; => "The capital of France is Paris."
```

### With System Prompt

```clojure
(def response (router/chat :openai
                "Explain quantum entanglement"
                :system-prompt "You are a physics professor."))
```

### Multiple Messages

```clojure
(def response (router/completion :openai
                {:messages [{:role :system :content "You are a helpful assistant."}
                           {:role :user :content "What is 2+2?"}
                           {:role :assistant :content "4"}
                           {:role :user :content "What about 3+3?"}]}))
```

## Next Steps

- Learn about the [Router API](/doc/router-api.md) for configuration-based workflows
- Explore the [Core API](/doc/core-api.md) for direct provider access
- Discover [streaming responses](/doc/streaming.md)
- Check out [examples](/doc/examples.md) for common patterns
- Learn about [error handling](/docs/ERROR_HANDLING.md)

## Supported Providers

| Provider     | Status | Models |
|--------------|--------|--------|
| OpenAI       | ✅     | GPT-3.5-Turbo, GPT-4, GPT-4o |
| Anthropic    | ✅     | Claude 3 (Opus, Sonnet, Haiku) |
| Google Gemini| ✅     | Gemini Pro, Ultra |
| Mistral      | ✅     | Mistral Small/Medium/Large |
| OpenRouter   | ✅     | All OpenRouter models |
| Ollama       | ✅     | Local models |

## Getting Help

- 📖 Check the [API Guide](/doc/api-guide.md)
- 💻 Browse [examples](/doc/examples.md)
- 🐛 Report issues on [GitHub](https://github.com/unravel-team/clj-litellm/issues)
