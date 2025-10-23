# LiteLLM Clojure

A Clojure port of the popular [LiteLLM](https://github.com/BerriAI/litellm) library, providing a unified interface for multiple LLM providers with comprehensive observability and thread pool management.

[![Clojars Project](https://img.shields.io/clojars/v/tech.unravel/litellm-clj.svg)](https://clojars.org/tech.unravel/litellm-clj)
[![cljdoc](https://cljdoc.org/badge/tech.unravel/litellm-clj)](https://cljdoc.org/d/tech.unravel/litellm-clj)
[![Continuous Integration Tests](https://github.com/unravel-team/clj-litellm/actions/workflows/test.yml/badge.svg)](https://github.com/unravel-team/clj-litellm/actions/workflows/test.yml)
[![Lint](https://github.com/unravel-team/clj-litellm/actions/workflows/lint.yml/badge.svg)](https://github.com/unravel-team/clj-litellm/actions/workflows/lint.yml)

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Model Provider Support](#model-provider-support)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage Examples](#usage-examples)
  - [Basic Completion](#basic-completion)
  - [Streaming Responses](#streaming-responses)
  - [Function Calling](#function-calling-openai)
- [Provider Configuration](#provider-configuration)
  - [OpenAI](#openai)
  - [Anthropic (Claude)](#anthropic-claude)
  - [OpenRouter](#openrouter)
  - [Google Gemini](#google-gemini)
  - [Mistral AI](#mistral-ai)
  - [Ollama (Local Models)](#ollama-local-models)
- [Configuration](#configuration)
  - [System Configuration](#system-configuration)
  - [Request Options](#request-options)
- [Advanced Features](#advanced-features)
  - [Health Monitoring](#health-monitoring)
  - [Cost Tracking](#cost-tracking)
- [Documentation](#documentation)
- [License](#license)
- [Acknowledgments](#acknowledgments)

---

## Overview

LiteLLM Clojure provides a unified, idiomatic Clojure interface for interacting with multiple Large Language Model (LLM) providers. Whether you're using OpenAI, Anthropic, Google Gemini, or any other supported provider, you can use the same API with consistent patterns.

**Key Benefits:**
- Switch between providers without changing your code
- Built-in async support with proper context propagation
- Comprehensive observability and metrics
- Thread pool management for optimal performance
- Cost tracking and token estimation

---

## Features

- **Unified API**: Single interface for multiple LLM providers
- **Async Operations**: Non-blocking API calls with proper context propagation
- **Provider Abstraction**: Easy to add new LLM providers
- **Health Monitoring**: System health checks and metrics
- **Cost Tracking**: Built-in token counting and cost estimation
- **Streaming Support**: Stream responses for better UX
- **Function Calling**: Support for OpenAI-style function calling

---

## Model Provider Support

### Currently Supported Providers

| Provider     | Status       | Models                                     | Function Calling | Streaming |
|--------------|--------------|--------------------------------------------|------------------|-----------|
| OpenAI       | ✅ Supported | GPT-3.5-Turbo, GPT-4, GPT-4o               | ✅               | ✅        |
| Anthropic    | ✅ Supported | Claude 3 (Opus, Sonnet, Haiku), Claude 2.x | ❌               | ✅        |
| OpenRouter   | ✅ Supported | All OpenRouter models                      | ✅               | ✅        |
| Google Gemini| ✅ Supported | Gemini Pro, Gemini Pro Vision, Gemini Ultra| ❌               | ✅        |
| Mistral      | ✅ Supported | Mistral Small/Medium/Large, Codestral, Magistral | ✅               | ✅        |
| Ollama       | ✅ Supported | Local models                               | ❌               | ✅        |

### Planned Providers

- Azure OpenAI
- Cohere
- Hugging Face
- Together AI
- Replicate
- And more...

---

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

---

## Quick Start

```clojure
(require '[litellm.core :as litellm])

;; Start the LiteLLM system
(def system (litellm/start-system {:telemetry {:enabled true}
                                    :thread-pools {:io-pool-size 10
                                                   :cpu-pool-size 4}}))

;; Make a completion request
(def response @(litellm/completion system
                 {:model "gpt-3.5-turbo"
                  :messages [{:role "user" :content "Hello, how are you?"}]}))

;; Access the response
(println (-> response :choices first :message :content))

;; Stop the system when done
(litellm/stop-system system)
```

---

## Usage Examples

### Basic Completion

```clojure
(require '[litellm.core :as litellm])

(def system (litellm/start-system {}))

;; Simple completion
(def response @(litellm/completion system
                 {:model "gpt-3.5-turbo"
                  :messages [{:role "user" :content "Explain quantum computing"}]
                  :max_tokens 100}))
```

### Streaming Responses

```clojure
;; Stream responses for better UX
(litellm/completion system
  {:model "gpt-4"
   :messages [{:role "user" :content "Write a poem"}]
   :stream true}
  {:on-chunk (fn [chunk]
               (print (-> chunk :choices first :delta :content)))
   :on-complete (fn [response]
                  (println "\nStream complete!"))
   :on-error (fn [error]
               (println "Error:" error))})
```

### Function Calling (OpenAI)

```clojure
(def response @(litellm/completion system
                 {:model "gpt-4"
                  :messages [{:role "user" :content "What's the weather in Boston?"}]
                  :functions [{:name "get_weather"
                              :description "Get the current weather"
                              :parameters {:type "object"
                                          :properties {:location {:type "string"
                                                                 :description "City name"}}
                                          :required ["location"]}}]}))
```

---

## Provider Configuration

### OpenAI

Set your API key as an environment variable:

```bash
export OPENAI_API_KEY=your-api-key-here
```

```clojure
(litellm/completion system
  {:model "gpt-4"
   :messages [{:role "user" :content "Hello!"}]})
```

### Anthropic (Claude)

Set your API key:

```bash
export ANTHROPIC_API_KEY=your-api-key-here
```

```clojure
(litellm/completion system
  {:model "claude-3-opus-20240229"
   :messages [{:role "user" :content "Hello Claude!"}]
   :max_tokens 1024})
```

### OpenRouter

OpenRouter provides access to multiple LLM providers through a single API:

```bash
export OPENROUTER_API_KEY=your-api-key-here
```

```clojure
;; Use OpenAI models via OpenRouter
(litellm/completion system
  {:model "openai/gpt-4"
   :messages [{:role "user" :content "Hello!"}]})

;; Use Anthropic models via OpenRouter
(litellm/completion system
  {:model "anthropic/claude-3-opus"
   :messages [{:role "user" :content "Hello!"}]})

;; Use Meta models via OpenRouter
(litellm/completion system
  {:model "meta-llama/llama-2-70b-chat"
   :messages [{:role "user" :content "Hello!"}]})
```

### Google Gemini

Set your API key:

```bash
export GEMINI_API_KEY=your-api-key-here
```

```clojure
;; Use Gemini Pro
(litellm/completion system
  {:model "gemini-pro"
   :messages [{:role "user" :content "Explain quantum computing"}]})

;; Use Gemini Pro Vision with images
(litellm/completion system
  {:model "gemini-pro-vision"
   :messages [{:role "user" 
               :content [{:type "text" :text "What's in this image?"}
                        {:type "image_url" :image_url {:url "https://..."}}]}]})

;; Configure safety settings and generation params
(litellm/completion system
  {:model "gemini-pro"
   :messages [{:role "user" :content "Write a story"}]
   :temperature 0.9
   :top_p 0.95
   :top_k 40
   :max_tokens 1024})
```

### Mistral AI

Set your API key:

```bash
export MISTRAL_API_KEY=your-api-key-here
```

```clojure
;; Use Mistral Small for fast, cost-effective responses
(litellm/completion system
  {:model "mistral/mistral-small-latest"
   :messages [{:role "user" :content "Explain quantum computing"}]})

;; Use Mistral Large for complex reasoning tasks
(litellm/completion system
  {:model "mistral/mistral-large-latest"
   :messages [{:role "user" :content "Analyze this complex problem..."}]
   :temperature 0.7})

;; Use Codestral for code generation
(litellm/completion system
  {:model "mistral/codestral-latest"
   :messages [{:role "user" :content "Write a Clojure function to parse JSON"}]})

;; Use Magistral models with reasoning support
(litellm/completion system
  {:model "mistral/magistral-medium-2506"
   :messages [{:role "user" :content "Solve this math problem step by step"}]
   :reasoning-effort "medium"})  ;; Options: "low", "medium", "high"

;; Function calling with Mistral
(litellm/completion system
  {:model "mistral/mistral-large-latest"
   :messages [{:role "user" :content "What's the weather in Paris?"}]
   :tools [{:type "function"
            :function {:name "get_weather"
                      :description "Get current weather"
                      :parameters {:type "object"
                                  :properties {:location {:type "string"}}}}}]})
```

### Ollama (Local Models)

Run Ollama locally and use local models:

```clojure
(litellm/completion system
  {:model "ollama/llama2"
   :messages [{:role "user" :content "Hello!"}]
   :api_base "http://localhost:11434"})
```

---

## Configuration

### System Configuration

```clojure
(def system (litellm/start-system
  {:telemetry {:enabled true          ;; Enable observability
               :metrics-interval 60}  ;; Metrics collection interval (seconds)
   
   :thread-pools {:io-pool-size 10    ;; Thread pool for I/O operations
                  :cpu-pool-size 4}   ;; Thread pool for CPU-bound tasks
   
   :cache {:enabled true              ;; Enable response caching
           :ttl 3600}                 ;; Cache TTL in seconds
   
   :retry {:max-attempts 3            ;; Max retry attempts
           :backoff-ms 1000}}))       ;; Initial backoff delay
```

### Request Options

```clojure
{:model "gpt-4"                      ;; Model identifier
 :messages [{:role "user"            ;; Conversation messages
             :content "Hello"}]
 :max_tokens 100                     ;; Maximum tokens to generate
 :temperature 0.7                    ;; Sampling temperature (0.0-2.0)
 :top_p 1.0                          ;; Nucleus sampling
 :n 1                                ;; Number of completions
 :stream false                       ;; Enable streaming
 :stop ["\n"]                        ;; Stop sequences
 :presence_penalty 0.0               ;; Presence penalty (-2.0 to 2.0)
 :frequency_penalty 0.0              ;; Frequency penalty (-2.0 to 2.0)
 :user "user-123"}                   ;; User identifier for tracking
```

---

## Advanced Features

### Health Monitoring

```clojure
;; Check system health
(litellm/health-check system)
;; => {:status :healthy
;;     :providers {:openai :ready
;;                 :anthropic :ready}
;;     :thread-pools {:io-pool {:active 2 :size 10}
;;                    :cpu-pool {:active 0 :size 4}}}
```

### Cost Tracking

```clojure
;; Get cost estimate for a request
(def cost-info (litellm/estimate-cost
                 {:model "gpt-4"
                  :messages [{:role "user" :content "Hello"}]}))
;; => {:estimated-tokens 50
;;     :estimated-cost-usd 0.0015}
```

---

## Documentation

- **[API Guide](docs/API_GUIDE.md)** - Comprehensive API reference
- **[Streaming Guide](docs/STREAMING_GUIDE.md)** - Detailed streaming documentation
- **[Migration Guide](docs/MIGRATION_GUIDE.md)** - Migrating from other libraries
- **[Namespaces](docs/NAMESPACES.md)** - Namespace organization and structure
- **[Examples](examples/)** - More code examples

---

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## Acknowledgments

- [LiteLLM](https://github.com/BerriAI/litellm) - The original Python library that inspired this port
