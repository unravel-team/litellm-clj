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
    
## Usage Examples

### Example 1: Quick Script (Router API)

```clojure
(require '[litellm.router :as llm])

(llm/quick-setup!)

(let [response (llm/chat :openai "What is the capital of France?")]
   (println (llm/extract-content response))))
```

### Example 2: Direct Core API (No Configuration)

```clojure
(require '[litellm.core :as core])

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
