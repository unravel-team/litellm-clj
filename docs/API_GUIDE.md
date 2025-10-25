# LiteLLM API Guide

## Quick Start

The recommended way to use LiteLLM is through the `litellm.router` namespace:

```clojure
(require '[litellm.router :as llm])

;; One-time setup from environment variables
(llm/quick-setup!)

;; Use registered configs
(llm/chat :openai "What is 2+2?")

;; Or register custom configs
(llm/register! :fast 
  {:provider :openai 
   :model "gpt-4o-mini" 
   :config {:api-key "sk-..."}})

(llm/completion :fast {:messages [{:role :user :content "Hello"}]})
```

For simple direct provider calls, use `litellm.core`:

```clojure
(require '[litellm.core :as core])

;; Direct call without configuration
(core/completion :openai "gpt-4o-mini"
                 {:messages [{:role :user :content "Hello"}]
                  :api-key "sk-..."})
```

## API Namespaces

### litellm.router (Recommended)

The primary public API combining configuration management with provider operations.

**Configuration Router:**
- `register!` - Register a provider configuration
- `unregister!` - Remove a configuration
- `list-configs` - List all registered configs
- `get-config` - Get a specific config
- `clear-router!` - Clear all configs

**Provider Discovery:**
- `list-providers` - List available providers
- `provider-available?` - Check if provider exists
- `provider-info` - Get provider information

**Completion API:**
- `completion` - Unified completion using registered configs
- `chat` - Simple chat function

**Quick Setup:**
- `quick-setup!` - Auto-configure from environment variables
- `setup-openai!` - Configure OpenAI
- `setup-anthropic!` - Configure Anthropic
- `setup-ollama!` - Configure Ollama
- `setup-mistral!` - Configure Mistral
- `setup-gemini!` - Configure Gemini
- `setup-openrouter!` - Configure OpenRouter

**Utilities:**
- `extract-content` - Extract response content
- `extract-message` - Extract response message
- `extract-usage` - Extract token usage
- `estimate-tokens` - Estimate token count
- `calculate-cost` - Calculate request cost

### litellm.core

Direct provider API for simple use cases.

**Functions:**
- `completion` - Direct completion with provider and model
- `chat` - Simple chat function
- `list-providers` - Provider discovery
- `provider-available?` - Check provider availability
- Provider-specific functions: `openai-completion`, `anthropic-completion`, etc.
- All utility functions (extract-content, estimate-tokens, etc.)

### Advanced: Custom Threadpool Management

For users requiring custom threadpool management, observability, or lifecycle control, see `examples.system` as a reference implementation showing how to build advanced systems using `litellm.threadpool` utilities.

**Available Utilities (litellm.threadpool):**
- `create-thread-pools` - Create custom threadpools
- `pool-health` / `all-pools-health` - Health monitoring
- `pool-utilization` / `pool-pressure` - Performance metrics
- `start-pool-monitoring` - Background monitoring with callbacks
- `pool-performance-report` - Comprehensive metrics
- `shutdown-pools!` - Graceful shutdown

## Usage Examples

### Example 1: Quick Script (Router API)

```clojure
(ns my-script
  (:require [litellm.router :as llm]))

(defn -main []
  (llm/quick-setup!)
  
  (let [response (llm/chat :openai "What is the capital of France?")]
    (println (llm/extract-content response))))
```

### Example 2: Direct Core API (No Configuration)

```clojure
(ns my-app
  (:require [litellm.core :as core]))

;; Direct provider calls without registration
(let [response (core/completion :openai "gpt-4o-mini"
                                {:messages [{:role :user :content "Hello"}]
                                 :api-key (System/getenv "OPENAI_API_KEY")})]
  (println (core/extract-content response)))
```

### Example 3: Custom Configuration (Router)

```clojure
(ns my-app
  (:require [litellm.router :as llm]))

;; Register a fast config
(llm/register! :fast
  {:provider :openai
   :model "gpt-4o-mini"
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Register a premium config
(llm/register! :premium
  {:provider :anthropic
   :model "claude-3-opus"
   :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})

;; Use them
(llm/chat :fast "Simple question")
(llm/chat :premium "Complex analysis task")
```

### Example 4: Dynamic Router Configuration

```clojure
(ns my-app
  (:require [litellm.router :as llm]))

;; Smart routing based on request
(llm/register! :smart
  {:router (fn [{:keys [priority user-tier]}]
             (cond
               (= user-tier :premium) {:provider :anthropic :model "claude-3-opus"}
               (= priority :high) {:provider :openai :model "gpt-4"}
               :else {:provider :openai :model "gpt-4o-mini"}))
   :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
             :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})

;; Use with routing parameters
(llm/completion :smart 
  {:messages [{:role :user :content "Hello"}]
   :priority :high
   :user-tier :premium})
```

### Example 5: Long-Running Application

```clojure
(ns my-app
  (:require [litellm.router :as llm]))

(defonce config-state (atom nil))

(defn start! []
  ;; Setup configurations
  (llm/quick-setup!)
  
  ;; Add custom configs
  (llm/register! :production
    {:router (fn [{:keys [task-type]}]
               (case task-type
                 :code {:provider :anthropic :model "claude-3-sonnet"}
                 :chat {:provider :openai :model "gpt-4o-mini"}
                 {:provider :openai :model "gpt-4o-mini"}))
     :configs {:openai {:api-key (System/getenv "OPENAI_API_KEY")}
               :anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}})
  
  (reset! config-state :started)
  (println "LiteLLM configured successfully"))

(defn stop! []
  (llm/clear-router!)
  (reset! config-state nil)
  (println "LiteLLM shutdown complete"))

(defn handle-request [task-type message]
  (llm/completion :production
    {:messages [{:role :user :content message}]
     :task-type task-type}))
```

### Example 6: Advanced - Custom Threadpool Management

```clojure
(ns my-app
  (:require [examples.system :as system]
            [litellm.threadpool :as tp]
            [clojure.core.async :refer [<!!]]))

;; Create custom system with specific threadpool config
(def my-system 
  (system/create-system
    {:providers {:openai {:api-key (System/getenv "OPENAI_API_KEY")}}
     :thread-pools {:api-calls {:pool-size 100 :queue-size 2000}
                    :monitoring {:pool-size 5}}}))

(try
  ;; Use the system for high-concurrency requests
  (let [response (system/completion my-system :openai "gpt-4"
                                    {:messages [{:role :user :content "Hello"}]})]
    (println (-> response :choices first :message :content)))
  
  ;; Monitor threadpool health
  (println "Pool health:" (tp/all-pools-health (:thread-pools my-system)))
  
  ;; Get performance metrics
  (println "Performance:" (tp/pool-performance-report (:thread-pools my-system)))
  
  (finally
    (system/shutdown-system! my-system)))
```

## Configuration Options

### Simple Configuration

```clojure
{:provider :openai
 :model "gpt-4o-mini"
 :config {:api-key "sk-..."}}
```

### Router Configuration

```clojure
{:router (fn [request] 
           ;; Return {:provider :xxx :model "xxx"}
           {:provider :openai :model "gpt-4o-mini"})
 :configs {:openai {:api-key "sk-..."}
           :anthropic {:api-key "sk-..."}}}
```

## Best Practices

1. **Use litellm.router** - For configuration-based workflows; use litellm.core for simple direct calls
2. **Use quick-setup!** - For simple cases, let environment variables configure everything
3. **Register configs** - Use meaningful names like :fast, :premium, :production
4. **Use routers** - For dynamic provider selection based on request parameters
5. **Custom threadpools** - Reference examples.system when you need high concurrency or observability
6. **Error handling** - Use retry wrappers for production reliability

## Common Patterns

### A/B Testing

```clojure
(llm/register! :ab-test
  {:router (fn [_] 
             (if (< (rand) 0.5)
               {:provider :openai :model "gpt-4o-mini"}
               {:provider :anthropic :model "claude-3-haiku"}))
   :configs {:openai {:api-key "..."}
             :anthropic {:api-key "..."}}})
```

### Fallback Chain

```clojure
(wrap/with-fallback [:premium :fast :local]
  {:messages [{:role :user :content "Hello"}]}
  llm/completion)
```

### Cost Optimization

```clojure
(llm/register! :cost-optimized
  {:router (fn [{:keys [user-tier]}]
             (if (= user-tier :free)
               {:provider :ollama :model "llama3"}
               {:provider :openai :model "gpt-4o-mini"}))
   :configs {:openai {:api-key "..."}
             :ollama {:api-base "http://localhost:11434"}}})
```
