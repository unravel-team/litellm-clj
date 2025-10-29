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
- [Documentation](#documentation)
- [License](#license)
- [Acknowledgments](#acknowledgments)

---

## Overview

LiteLLM Clojure provides a unified, idiomatic Clojure interface for interacting with multiple Large Language Model (LLM) providers. Whether you're using OpenAI, Anthropic, Google Gemini, or any other supported provider, you can use the same API with consistent patterns.

**Key Benefits:**
- Switch between providers without changing your code
- Streaming support with core.async channels
- Router API to switch between models in runtime
- Function calling support (Alpha)
---

## Model Provider Support

### Currently Supported Providers

| Provider     | Status       | Models                                     | Function Calling | Streaming |
|--------------|--------------|--------------------------------------------|------------------|-----------|
| OpenAI       | ✅ Supported | GPT-3.5-Turbo, GPT-4, GPT-4o               | ✅               | ✅        |
| Anthropic    | ✅ Supported | Claude 3 (Opus, Sonnet, Haiku), Claude 2.x | ✅               | ✅        |
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
{:deps {tech.unravel/litellm-clj {:mvn/version "0.3.0-alpha"}}}
```

### Using Leiningen

Add to your `project.clj`:

```clojure
[tech.unravel/litellm-clj "0.3.0-alpha"]
```

---

## Quick Start

### Option 1 : Router API (litellm.router) - Recommended

For configuration-based workflows with named configs:

```clojure
(require '[litellm.router :as router])

;; Quick setup from environment variables
(router/quick-setup!)

;; Or register custom configurations
(router/register! :fast 
  {:provider :openai 
   :model "gpt-4o-mini" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Use registered configs
(def response (router/completion :fast 
                {:messages [{:role :user :content "Hello, how are you?"}]}))

;; Access the response
(println (router/extract-content response))
```

### Option 2: Direct API (litellm.core)

For simple, direct provider calls:

```clojure
```clojure
(require '[litellm.core :as core])

;; Direct provider calls without registration
(let [response (core/completion :openai "gpt-4o-mini"
                                {:messages [{:role :user :content "Hello"}]
                                 :api-key (System/getenv "OPENAI_API_KEY")})]
  (println (core/extract-content response)))
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
                 :tools [{:type "function"
                         :function {:name "get_weather"
                                   :description "Get the current weather"
                                   :parameters {:type "object"
                                               :properties {:location {:type "string"
                                                                      :description "City name"}}
                                               :required ["location"]}}}]}
                {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Check for function call
(let [message (llm/extract-message response)]
  (when-let [tool-calls (:tool-calls message)]
    (doseq [tool-call tool-calls]
       (println "Tool to call:" (get-in tool-call [:function :name]))
       (println "Arguments:" (get-in tool-call [:function :arguments])))))
```

---

## Documentation
- **[API Guide](docs/API_GUIDE.md)** - Comprehensive API reference
- **[Error Handling](docs/ERROR_HANDLING.md)** - Examples related to error handling
- **[Streaming Guide](docs/STREAMING_GUIDE.md)** - Detailed streaming documentation
- **[Examples](examples/)** - More code examples

---

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## Acknowledgments

- [LiteLLM](https://github.com/BerriAI/litellm) - The original Python library that inspired this port
