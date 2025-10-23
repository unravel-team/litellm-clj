# LiteLLM API Guide

## Quick Start

The recommended way to use LiteLLM is through the unified `litellm.api` namespace:

```clojure
(ns my-app
  (:require [litellm.api :as llm]))

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

## API Namespaces

### litellm.api (Recommended)

The unified public API combining registry system with provider operations.

**Configuration Registry:**
- `register!` - Register a provider configuration
- `unregister!` - Remove a configuration
- `list-configs` - List all registered configs
- `get-config` - Get a specific config
- `clear-registry!` - Clear all configs

**Provider Discovery:**
- `list-providers` - List available providers
- `provider-available?` - Check if provider exists
- `provider-info` - Get provider information

**Completion API:**
- `completion` - Unified completion (works with configs or direct models)
- `chat` - Simple chat function

**Quick Setup:**
- `quick-setup!` - Auto-configure from environment variables
- `setup-openai!` - Configure OpenAI
- `setup-anthropic!` - Configure Anthropic
- `setup-ollama!` - Configure Ollama

**Utilities:**
- `parse-model` - Parse model strings
- `extract-content` - Extract response content
- `extract-message` - Extract response message
- `extract-usage` - Extract token usage
- `estimate-tokens` - Estimate token count
- `calculate-cost` - Calculate request cost

### litellm.core

Provider-focused, system-independent API.

**Functions:**
- `completion` - Direct completion with model strings
- `chat` - Simple chat function
- `list-providers` - Provider discovery
- `provider-available?` - Check provider availability
- All utility functions (parse-model, extract-content, etc.)

### litellm.system

System lifecycle and management for advanced features.

**Functions:**
- `create-system` - Create LiteLLM system
- `shutdown-system!` - Shutdown system
- `completion` - System-based completion (supports streaming)
- `with-system` - Execute with system context
- `health-check` - System health checks
- `test-providers` - Test all providers

### litellm.config

Low-level configuration registry.

**Functions:**
- `register!` - Register configuration
- `unregister!` - Remove configuration
- `resolve-config` - Resolve configuration with routing
- `list-configs` - List all configs

## Usage Examples

### Example 1: Quick Script

```clojure
(ns my-script
  (:require [litellm.api :as llm]))

(defn -main []
  (llm/quick-setup!)
  
  (let [response (llm/chat :openai "What is the capital of France?")]
    (println (llm/extract-content response))))
```

### Example 2: Custom Configuration

```clojure
(ns my-app
  (:require [litellm.api :as llm]))

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

### Example 3: Router Configuration

```clojure
(ns my-app
  (:require [litellm.api :as llm]))

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

### Example 4: Direct Model Strings (No Registry)

```clojure
(ns my-app
  (:require [litellm.api :as llm]))

;; Direct completion without registration
(llm/completion :model "openai/gpt-4"
                :messages [{:role :user :content "Hello"}]
                :api-key "sk-...")

;; Also works with chat
(llm/chat "openai/gpt-4" "Hello!" :api-key "sk-...")
```

### Example 5: Long-Running Application

```clojure
(ns my-app
  (:require [litellm.api :as llm]))

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
  (llm/clear-registry!)
  (reset! config-state nil)
  (println "LiteLLM shutdown complete"))

(defn handle-request [task-type message]
  (llm/completion :production
    {:messages [{:role :user :content message}]
     :task-type task-type}))
```

### Example 6: With Wrappers (Retry, Timeout, etc.)

```clojure
(ns my-app
  (:require [litellm.api :as llm]
            [litellm.wrappers :as wrap]))

;; Setup
(llm/quick-setup!)

;; Use with retry wrapper
(wrap/with-retry :openai
  {:messages [{:role :user :content "Hello"}]}
  llm/completion
  {:max-attempts 3
   :backoff-ms 1000})

;; Compose multiple wrappers
(wrap/compose-wrappers
  [(partial wrap/with-retry _ _ _ {:max-attempts 3})
   (partial wrap/with-timeout _ _ _ {:timeout-ms 30000})
   (partial wrap/with-cost-tracking _ _ _
     (fn [cost usage resp] 
       (println "Cost:" cost "USD")))]
  :openai
  {:messages [{:role :user :content "Hello"}]}
  llm/completion)
```

### Example 7: System-Based (for Streaming)

```clojure
(ns my-app
  (:require [litellm.api :as llm]))

;; Create system for advanced features
(def system (llm/create-system
              {:providers {"openai" {:provider :openai
                                     :api-key (System/getenv "OPENAI_API_KEY")}}
               :thread-pools {:api-calls {:pool-size 50}}}))

;; Use system for streaming
(llm/system-completion system
  :model "gpt-4"
  :messages [{:role :user :content "Hello"}]
  :stream true)

;; Don't forget to shutdown
(llm/shutdown-system! system)
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

1. **Use litellm.api** - It's the most convenient and recommended interface
2. **Use quick-setup!** - For simple cases, let environment variables configure everything
3. **Register configs** - Use meaningful names like :fast, :premium, :production
4. **Use routers** - For dynamic provider selection based on request parameters
5. **Cost tracking** - Use wrappers to monitor API costs
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
