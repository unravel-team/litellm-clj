# LiteLLM Namespaces Guide

LiteLLM provides three distinct namespaces, each designed for different use cases and levels of abstraction:

1. **`litellm.core`** - Direct provider calls with model names as-is
2. **`litellm.system`** - System-based API with lifecycle management and streaming
3. **`litellm.registry`** - Configuration-based API with named configs and routing

## Quick Comparison

| Feature | litellm.core | litellm.system | litellm.registry |
|---------|-------------|----------------|------------------|
| Complexity | Low | Medium | Medium |
| Configuration | None | System config | Named configs |
| Thread Pools | No | Yes | No |
| Streaming | No | Yes | No |
| Lifecycle Management | No | Yes | No |
| Routing | No | No | Yes |
| API Key Management | Manual | System config | Registry |
| Best For | Simple calls | Production, streaming | Multi-provider apps |

---

## litellm.core

### Purpose
Direct, low-level calls to LLM providers with no abstraction layers. Perfect for simple use cases where you want complete control.

### Key Features
- No configuration management
- No thread pools
- No streaming
- Model names exactly as providers expect them
- Direct function calls per provider

### Usage

```clojure
(require '[litellm.core :as core])

;; Basic completion
(core/completion :openai "gpt-4" 
                {:messages [{:role :user :content "Hello"}]
                 :max-tokens 100}
                {:api-key "sk-..."})

;; Provider-specific functions
(core/openai-completion "gpt-4" 
                       {:messages [{:role :user :content "Hello"}]}
                       :api-key "sk-...")

(core/anthropic-completion "claude-3-sonnet-20240229"
                          {:messages [{:role :user :content "Hello"}]}
                          :api-key "sk-ant-...")

;; Simple chat helper
(core/chat :openai "gpt-4" "What is 2+2?"
          :api-key "sk-..."
          :system-prompt "You are a math tutor")
```

### When to Use
- Simple scripts or tools
- You want complete control over API calls
- You don't need streaming
- You don't need thread pools
- You want to manage API keys yourself

---

## litellm.system

### Purpose
System-based API with lifecycle management, thread pools, and streaming support. Built for production applications that need advanced features.

### Key Features
- Thread pool management for concurrent requests
- Streaming support
- System lifecycle (create, shutdown)
- Health checks and monitoring
- Async operations

### Usage

```clojure
(require '[litellm.system :as system])

;; Create a system
(def sys (system/create-system
          {:providers {:openai {:api-key "sk-..."}}
           :thread-pools {:api-calls {:pool-size 10}
                         :health-checks {:pool-size 2}}}))

;; Make requests
(system/completion sys :openai "gpt-4"
                  {:messages [{:role :user :content "Hello"}]
                   :max-tokens 100})

;; Streaming
(let [stream-ch (system/completion sys
                                  {:provider :openai
                                   :model "gpt-4"
                                   :messages [{:role :user :content "Hello"}]
                                   :stream true})]
  (loop []
    (when-let [chunk (<!! stream-ch)]
      (print (-> chunk :choices first :delta :content))
      (recur))))

;; Always shutdown when done
(system/shutdown-system! sys)

;; Or use with-config for automatic lifecycle
(system/with-config
  {:providers {:openai {:api-key "sk-..."}}}
  (let [sys (system/get-global-system)]
    (system/completion sys :openai "gpt-4" {...})))
```

### When to Use
- Production applications
- You need streaming
- You need concurrent request handling
- You want health checks and monitoring
- You need proper lifecycle management

---

## litellm.registry

### Purpose
Configuration-based API that lets you register named configurations and use dynamic routing to select providers based on request properties.

### Key Features
- Named configurations
- Dynamic routing functions
- Multi-provider support with fallbacks
- Quick setup from environment variables
- Router-based provider selection

### Usage

```clojure
(require '[litellm.registry :as registry])

;; Register a simple configuration
(registry/register! :fast
                   {:provider :openai
                    :model "gpt-4o-mini"
                    :config {:api-key "sk-..."}})

;; Use registered config
(registry/completion :fast
                    {:messages [{:role :user :content "Hello"}]})

;; Register with routing
(registry/register! :smart
                   {:router (fn [request]
                             (if (> (count (:messages request)) 5)
                               {:provider :anthropic :model "claude-3-opus"}
                               {:provider :openai :model "gpt-4o-mini"}))
                    :configs {:openai {:api-key "sk-..."}
                             :anthropic {:api-key "sk-ant-..."}}})

;; Router automatically selects provider
(registry/completion :smart {:messages [...]})

;; Quick setup from environment
(registry/quick-setup!)  ; Auto-registers from env vars
(registry/completion :openai {:messages [...]})

;; Custom routers
(def router-config
  (registry/create-router
    (fn [{:keys [priority]}]
      (case priority
        :high {:provider :anthropic :model "claude-3-opus"}
        :low {:provider :openai :model "gpt-4o-mini"}
        {:provider :openai :model "gpt-4"}))
    {:openai {:api-key "sk-..."}
     :anthropic {:api-key "sk-ant-..."}}))

(registry/register! :priority router-config)
(registry/completion :priority
                    {:messages [...]
                     :priority :high})
```

### When to Use
- Multi-provider applications
- You want to switch providers easily
- You need dynamic routing based on request properties
- You want named configurations for different use cases
- You want centralized API key management

---

## Choosing the Right Namespace

### Use `litellm.core` if:
- ✅ You're building a simple script or tool
- ✅ You want direct control over provider calls
- ✅ You don't need streaming
- ✅ You're okay managing API keys manually
- ❌ You need streaming support
- ❌ You need concurrent request handling

### Use `litellm.system` if:
- ✅ You're building a production application
- ✅ You need streaming support
- ✅ You want thread pool management
- ✅ You need health checks and monitoring
- ✅ You want proper lifecycle management
- ❌ Your use case is too simple for this complexity

### Use `litellm.registry` if:
- ✅ You have multiple providers
- ✅ You want to switch providers easily
- ✅ You need dynamic routing
- ✅ You want named configurations
- ✅ You want centralized configuration management
- ❌ You only use one provider
- ❌ You don't need provider switching

---

## Can You Mix Namespaces?

**Yes!** The namespaces are designed to work together:

- `litellm.registry` uses `litellm.core` internally for actual provider calls
- `litellm.system` uses `litellm.providers.core` (the multimethod layer) for provider dispatch
- You can use `litellm.core` for some requests and `litellm.registry` for others

Example:
```clojure
;; Use registry for most requests
(registry/quick-setup!)
(registry/completion :openai {:messages [...]})

;; But drop down to core for special cases
(core/completion :openai "o1-preview"  ; Special model
                {:messages [...]}
                {:api-key "sk-..."})
```

---

## Migration Guide

### From old `litellm.api` to new namespaces

**Old code:**
```clojure
(require '[litellm.api :as api])

;; Direct call
(api/completion :model "openai/gpt-4"
               :messages [...]
               :api-key "sk-...")

;; Registry-based
(api/register! :fast {...})
(api/completion :fast {...})
```

**New code:**
```clojure
;; For direct calls, use litellm.core
(require '[litellm.core :as core])
(core/completion :openai "gpt-4"
                {:messages [...]}
                {:api-key "sk-..."})

;; For registry-based, use litellm.registry
(require '[litellm.registry :as registry])
(registry/register! :fast {...})
(registry/completion :fast {...})

;; For streaming, use litellm.system
(require '[litellm.system :as system])
(def sys (system/create-system {...}))
(system/completion sys :openai "gpt-4" {...})
```

---

## Examples

See the `examples/` directory for comprehensive examples:
- `examples/core_usage.clj` - litellm.core examples
- `examples/registry_usage.clj` - litellm.registry examples
- `examples/system_usage.clj` - litellm.system examples
