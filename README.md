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

### Option 1: Direct API (litellm.core)

```clojure
(require '[litellm.core :as llm])

;; Make a completion request
(def response (llm/completion :openai "gpt-4o-mini"
                {:messages [{:role :user :content "Hello, how are you?"}]}
                {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Access the response
(println (llm/extract-content response))
```

### Option 2: System-based API (litellm.system)

```clojure
(require '[litellm.system :as system])

;; Create a system with configuration
(def sys (system/create-system
          {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
           :thread-pools {:api-calls {:pool-size 10}}}))

;; Make a completion request
(def response (system/completion sys :openai "gpt-4o-mini"
                {:messages [{:role :user :content "Hello, how are you?"}]}))

;; Access the response
(println (-> response :choices first :message :content))

;; Shutdown the system when done
(system/shutdown-system! sys)
```

---

## Usage Examples

### Basic Completion

```clojure
(require '[litellm.core :as llm])

;; Simple completion with OpenAI
(def response (llm/completion :openai "gpt-4o-mini"
                {:messages [{:role :user :content "Explain quantum computing"}]
                 :max-tokens 100}
                {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Extract the content
(println (llm/extract-content response))

;; Or use provider-specific convenience functions
(def response2 (llm/openai-completion "gpt-4o-mini"
                 {:messages [{:role :user :content "What is Clojure?"}]}
                 :api-key (System/getenv "OPENAI_API_KEY")))
```

### Streaming Responses

```clojure
(require '[litellm.core :as llm]
         '[litellm.streaming :as streaming]
         '[clojure.core.async :refer [go-loop <!]])

;; Stream responses - returns a core.async channel
(let [ch (llm/completion :openai "gpt-4"
           {:messages [{:role :user :content "Write a poem"}]
            :stream true}
           {:api-key (System/getenv "OPENAI_API_KEY")})]
  
  ;; Consume the stream with go-loop
  (go-loop []
    (when-let [chunk (<! ch)]
      (when-let [content (streaming/extract-content chunk)]
        (print content)
        (flush))
      (recur))))

;; Or use callback-based API
(let [ch (llm/completion :openai "gpt-4"
           {:messages [{:role :user :content "Write a poem"}]
            :stream true}
           {:api-key (System/getenv "OPENAI_API_KEY")})]
  (streaming/consume-stream-with-callbacks ch
    (fn [chunk] (print (streaming/extract-content chunk)))
    (fn [response] (println "\nStream complete!"))
    (fn [error] (println "Error:" error))))
```

### Function Calling (OpenAI)

```clojure
(require '[litellm.core :as llm])

(def response (llm/completion :openai "gpt-4"
                {:messages [{:role :user :content "What's the weather in Boston?"}]
                 :functions [{:name "get_weather"
                             :description "Get the current weather"
                             :parameters {:type "object"
                                         :properties {:location {:type "string"
                                                                :description "City name"}}
                                         :required ["location"]}}]}
                {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Check for function call
(let [message (llm/extract-message response)]
  (when-let [function-call (:function-call message)]
    (println "Function to call:" (:name function-call))
    (println "Arguments:" (:arguments function-call))))
```

---

## Provider Configuration

### OpenAI

Set your API key as an environment variable:

```bash
export OPENAI_API_KEY=your-api-key-here
```

```clojure
(require '[litellm.core :as llm])

(llm/completion :openai "gpt-4"
  {:messages [{:role :user :content "Hello!"}]}
  {:api-key (System/getenv "OPENAI_API_KEY")})
```

### Anthropic (Claude)

Set your API key:

```bash
export ANTHROPIC_API_KEY=your-api-key-here
```

```clojure
(require '[litellm.core :as llm])

(llm/completion :anthropic "claude-3-sonnet-20240229"
  {:messages [{:role :user :content "Hello Claude!"}]
   :max-tokens 1024}
  {:api-key (System/getenv "ANTHROPIC_API_KEY")})
```

### OpenRouter

OpenRouter provides access to multiple LLM providers through a single API:

```bash
export OPENROUTER_API_KEY=your-api-key-here
```

```clojure
(require '[litellm.core :as llm])

;; Use OpenAI models via OpenRouter
(llm/completion :openrouter "openai/gpt-4"
  {:messages [{:role :user :content "Hello!"}]}
  {:api-key (System/getenv "OPENROUTER_API_KEY")})

;; Use Anthropic models via OpenRouter
(llm/completion :openrouter "anthropic/claude-3-opus"
  {:messages [{:role :user :content "Hello!"}]}
  {:api-key (System/getenv "OPENROUTER_API_KEY")})

;; Use Meta models via OpenRouter
(llm/completion :openrouter "meta-llama/llama-2-70b-chat"
  {:messages [{:role :user :content "Hello!"}]}
  {:api-key (System/getenv "OPENROUTER_API_KEY")})
```

### Google Gemini

Set your API key:

```bash
export GEMINI_API_KEY=your-api-key-here
```

```clojure
(require '[litellm.core :as llm])

;; Use Gemini Pro
(llm/completion :gemini "gemini-pro"
  {:messages [{:role :user :content "Explain quantum computing"}]}
  {:api-key (System/getenv "GEMINI_API_KEY")})

;; Use Gemini Pro Vision with images
(llm/completion :gemini "gemini-pro-vision"
  {:messages [{:role :user 
               :content [{:type "text" :text "What's in this image?"}
                        {:type "image_url" :image_url {:url "https://..."}}]}]}
  {:api-key (System/getenv "GEMINI_API_KEY")})

;; Configure generation params
(llm/completion :gemini "gemini-pro"
  {:messages [{:role :user :content "Write a story"}]
   :temperature 0.9
   :top_p 0.95
   :top_k 40
   :max_tokens 1024}
  {:api-key (System/getenv "GEMINI_API_KEY")})
```

### Mistral AI

Set your API key:

```bash
export MISTRAL_API_KEY=your-api-key-here
```

```clojure
(require '[litellm.core :as llm])

;; Use Mistral Small for fast, cost-effective responses
(llm/completion :mistral "mistral-small-latest"
  {:messages [{:role :user :content "Explain quantum computing"}]}
  {:api-key (System/getenv "MISTRAL_API_KEY")})

;; Use Mistral Large for complex reasoning tasks
(llm/completion :mistral "mistral-large-latest"
  {:messages [{:role :user :content "Analyze this complex problem..."}]
   :temperature 0.7}
  {:api-key (System/getenv "MISTRAL_API_KEY")})

;; Use Codestral for code generation
(llm/completion :mistral "codestral-latest"
  {:messages [{:role :user :content "Write a Clojure function to parse JSON"}]}
  {:api-key (System/getenv "MISTRAL_API_KEY")})

;; Function calling with Mistral
(llm/completion :mistral "mistral-large-latest"
  {:messages [{:role :user :content "What's the weather in Paris?"}]
   :tools [{:type "function"
            :function {:name "get_weather"
                      :description "Get current weather"
                      :parameters {:type "object"
                                  :properties {:location {:type "string"}}}}}]}
  {:api-key (System/getenv "MISTRAL_API_KEY")})
```

### Ollama (Local Models)

Run Ollama locally and use local models:

```clojure
(require '[litellm.core :as llm])

(llm/completion :ollama "llama2"
  {:messages [{:role :user :content "Hello!"}]}
  {:api-base "http://localhost:11434"})
```

---

## Configuration

### System Configuration

For system-based API with thread pool management:

```clojure
(require '[litellm.system :as system])

(def sys (system/create-system
  {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}
   
   :thread-pools {:api-calls {:pool-size 50}      ;; Thread pool for API calls
                  :cache-ops {:pool-size 10}      ;; Thread pool for cache operations
                  :retries {:pool-size 20}        ;; Thread pool for retries
                  :health-checks {:pool-size 5}   ;; Thread pool for health checks
                  :monitoring {:pool-size 2}}}))  ;; Thread pool for monitoring

;; Use the system
(system/completion sys :openai "gpt-4o-mini"
  {:messages [{:role :user :content "Hello!"}]})

;; Always shutdown when done
(system/shutdown-system! sys)
```

### Request Options

```clojure
;; Request map (third parameter to completion)
{:messages [{:role :user             ;; Conversation messages
             :content "Hello"}]
 :max-tokens 100                     ;; Maximum tokens to generate
 :temperature 0.7                    ;; Sampling temperature (0.0-2.0)
 :top_p 1.0                          ;; Nucleus sampling
 :n 1                                ;; Number of completions
 :stream false                       ;; Enable streaming (returns core.async channel)
 :stop ["\n"]                        ;; Stop sequences
 :presence-penalty 0.0               ;; Presence penalty (-2.0 to 2.0)
 :frequency-penalty 0.0              ;; Frequency penalty (-2.0 to 2.0)
 :user "user-123"}                   ;; User identifier for tracking

;; Config map (fourth parameter to completion)
{:api-key "sk-..."                   ;; API key (or use env var)
 :api-base "https://..."             ;; Custom API base URL
 :timeout 30000}                     ;; Request timeout in milliseconds
```

---

## Advanced Features

### Health Monitoring

```clojure
(require '[litellm.system :as system])

;; Create a system
(def sys (system/create-system
          {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}}))

;; Check system health
(system/health-check sys)
;; => {:openai {:healthy true :latency-ms 120}}

;; Get system info
(system/system-info sys)
;; => {:providers {:openai {:name :openai :config {...}}}
;;     :thread-pools {...}
;;     :config {...}}
```

### Cost Tracking

```clojure
(require '[litellm.core :as llm])

;; Estimate tokens for text
(llm/estimate-tokens "Hello, world!")
;; => 4

;; Estimate tokens for a request
(llm/estimate-request-tokens
  {:messages [{:role :user :content "Hello, world!"}]})
;; => {:prompt-tokens 10 :total-tokens 10}

;; Calculate cost for a completed request
(llm/calculate-cost :openai "gpt-4" 100 50)
;; => {:prompt-cost 0.003 :completion-cost 0.003 :total-cost 0.006}
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
