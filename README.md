# LiteLLM Clojure

A Clojure port of the popular [LiteLLM](https://github.com/BerriAI/litellm) library, providing a unified interface for multiple LLM providers with comprehensive observability and thread pool management.

## Model Provider Support Matrix

| Provider     | Status       | Models                                     | Function Calling | Streaming |
|--------------|--------------|--------------------------------------------|------------------|-----------|
| OpenAI       | ✅ Supported | GPT-3.5-Turbo, GPT-4, GPT-4o               | ✅               | ✅        |
| Anthropic    | ✅ Supported | Claude 3 (Opus, Sonnet, Haiku), Claude 2.x | ❌               | ✅        |
| OpenRouter   | ✅ Supported | All OpenRouter models                      | ✅               | ✅        |
| Azure OpenAI | 🔄 Planned   | -                                          | -                | -         |
| Google       | 🔄 Planned   | Gemini, PaLM                               | -                | -         |
| Cohere       | 🔄 Planned   | Command                                    | -                | -         |
| Hugging Face | 🔄 Planned   | Various open models                        | -                | -         |
| Mistral      | 🔄 Planned   | Mistral, Mixtral                           | -                | -         |
| Ollama       | 🔄 Planned   | Local models                               | -                | -         |
| Together AI  | 🔄 Planned   | Various open models                        | -                | -         |
| Replicate    | 🔄 Planned   | Various open models                        | -                | -         |

## Features

- **Unified API**: Single interface for multiple LLM providers
- **Async Operations**: Non-blocking API calls with proper context propagation
- **Provider Abstraction**: Easy to add new LLM providers
- **Health Monitoring**: System health checks and metrics
- **Cost Tracking**: Built-in token counting and cost estimation
- **Streaming Support**: Stream responses for better UX
- **Function Calling**: Support for OpenAI-style function calling
## Currently Supported Providers

- **OpenAI**: GPT-3.5-Turbo, GPT-4, GPT-4o, and other OpenAI models
- **Anthropic**: Claude 3 (Opus, Sonnet, Haiku), Claude 2.x
- **OpenRouter**: Access to multiple providers through a single API (OpenAI, Anthropic, Google, Meta, etc.)

## Planned Providers

- Azure OpenAI
- Google (Gemini)
- Cohere
- Mistral AI
- Hugging Face
- Ollama (local models)
- Together AI
- Replicate
- And more...

## Installation

Add to your `deps.edn`:

```clojure
{:deps {tech.unravel/litellm-clj {:mvn/version "0.1.0"}}}
```

Or with Leiningen, add to your `project.clj`:

```clojure
[tech.unravel/litellm-clj "0.1.0"]
```

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

### Ollama (Local Models)

Run Ollama locally and use local models:

```clojure
(litellm/completion system
  {:model "ollama/llama2"
   :messages [{:role "user" :content "Hello!"}]
   :api_base "http://localhost:11434"})
```

## Configuration Options

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

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- [LiteLLM](https://github.com/BerriAI/litellm) - The original Python library that inspired this port
