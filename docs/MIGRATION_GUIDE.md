# Migration Guide: litellm.core Refactoring

## Overview

The `litellm.core` namespace has been refactored to separate system-specific concerns from provider-focused functionality. This guide will help you migrate your code to the new structure.

## What Changed?

### Before

Previously, `litellm.core` contained both:
- System lifecycle management (create-system, shutdown-system, etc.)
- Provider-based completion functions
- Thread pool management
- System state

### After

**litellm.core** - Provider-focused API:
- Provider registration and discovery
- Model-based completion functions
- Provider validation and utilities
- System-independent operations

**litellm.system** - System-specific functionality:
- System lifecycle (create-system, shutdown-system, init!, shutdown!)
- Thread pool management
- Global system state management
- System utilities (health-check, test-providers, benchmark-provider)
- System context macros (with-system, with-config)

## Migration Paths

### Path 1: Simple Usage (No System Required)

If you're using basic completion calls without advanced features like streaming or custom thread pools:

**Before:**
```clojure
(ns my-app
  (:require [litellm.core :as litellm]))

(let [system (litellm/create-system {:providers {"openai" {:provider :openai}}
                                      :thread-pools {:api-calls {:pool-size 50}}})]
  (try
    (litellm/completion :model "gpt-4"
                       :messages [{:role :user :content "Hello"}]
                       :api-key "sk-...")
    (finally
      (litellm/shutdown-system! system))))
```

**After:**
```clojure
(ns my-app
  (:require [litellm.core :as litellm]))

;; No system needed! Direct completion
(litellm/completion :model "openai/gpt-4"
                   :messages [{:role :user :content "Hello"}]
                   :api-key "sk-...")
```

### Path 2: Advanced Usage (With System)

If you need streaming, thread pool control, or other system features:

**Before:**
```clojure
(ns my-app
  (:require [litellm.core :as litellm]))

(let [system (litellm/create-system config)]
  (litellm/with-system system
    (litellm/completion ...)))
```

**After:**
```clojure
(ns my-app
  (:require [litellm.core :as core]
            [litellm.system :as system]))

(let [sys (system/create-system config)]
  (system/with-system sys
    (system/completion sys ...)))
```

## Detailed Changes

### 1. System Lifecycle Functions

**Before:**
```clojure
(require '[litellm.core :as litellm])

(def system (litellm/create-system config))
(litellm/shutdown-system! system)
(litellm/init! config)
(litellm/shutdown!)
```

**After:**
```clojure
(require '[litellm.system :as system])

(def sys (system/create-system config))
(system/shutdown-system! sys)
(system/init! config)
(system/shutdown!)
```

### 2. Completion Functions

#### System-Independent (NEW!)

```clojure
(require '[litellm.core :as core])

;; Simple completion - no system needed
(core/completion :model "openai/gpt-4"
                 :messages [{:role :user :content "Hello"}]
                 :api-key "sk-...")

;; Simple chat
(core/chat "openai/gpt-4" "Hello!" :api-key "sk-...")
```

#### System-Based

**Before:**
```clojure
(litellm/make-request system request)
(litellm/completion request)  ; uses global *system*
```

**After:**
```clojure
(system/make-request sys request)
(system/completion sys request)  ; explicit system parameter
```

### 3. Provider Discovery

**Before:**
```clojure
(litellm/list-providers system)
```

**After:**
```clojure
;; System-independent provider discovery
(core/list-providers)
(core/provider-available? :openai)
(core/provider-info :openai)
```

### 4. Model String Parsing

**Before:**
```clojure
(litellm.providers.core/extract-provider-name "openai/gpt-4")
```

**After:**
```clojure
(core/extract-provider "openai/gpt-4")  ;; => :openai
(core/extract-model-name "openai/gpt-4")  ;; => "gpt-4"
(core/parse-model "openai/gpt-4")  ;; => {:provider :openai :model "gpt-4"}
```

### 5. System Utilities

**Before:**
```clojure
(litellm/system-info system)
(litellm/health-check system)
(litellm/test-providers system)
(litellm/benchmark-provider system :openai 100)
```

**After:**
```clojure
(system/system-info sys)
(system/health-check sys)
(system/test-providers sys)
(system/benchmark-provider sys :openai 100)
```

### 6. Context Macros

**Before:**
```clojure
(litellm/with-system system
  ...)

(litellm/with-config config
  ...)
```

**After:**
```clojure
(system/with-system sys
  ...)

(system/with-config config
  ...)
```

## Common Patterns

### Pattern 1: Quick Script (No System)

```clojure
(ns my-script
  (:require [litellm.core :as llm]))

(defn -main []
  (let [response (llm/completion :model "openai/gpt-4"
                                 :messages [{:role :user :content "Hello"}])]
    (println (llm/extract-content response))))
```

### Pattern 2: Long-Running Application (With System)

```clojure
(ns my-app
  (:require [litellm.system :as llm-sys]
            [litellm.core :as llm-core]))

(defonce system (atom nil))

(defn start! []
  (reset! system
          (llm-sys/create-system
            {:providers {"openai" {:provider :openai
                                   :api-key (System/getenv "OPENAI_API_KEY")}}
             :thread-pools {:api-calls {:pool-size 50}}})))

(defn stop! []
  (when @system
    (llm-sys/shutdown-system! @system)
    (reset! system nil)))

(defn chat [message]
  (llm-sys/completion @system
                      :model "gpt-4"
                      :messages [{:role :user :content message}]))
```

### Pattern 3: Using Config Registry

```clojure
(ns my-app
  (:require [litellm.config :as config]
            [litellm.core :as core]))

;; Register configurations
(config/register! :fast
                  {:provider :openai
                   :model "gpt-4o-mini"
                   :config {:api-key "sk-..."}})

;; Use with wrappers for advanced features
(require '[litellm.wrappers :as wrap])

(wrap/with-retry :fast
                 {:messages [{:role :user :content "Hello"}]}
                 (fn [config-name request]
                   (let [resolved (config/resolve-config config-name request)]
                     (core/completion (merge request resolved))))
                 {:max-attempts 3})
```

## Breaking Changes

1. **System parameter is now explicit**: System-based completion functions now require the system as the first parameter instead of using a dynamic var.

2. **No global system in litellm.core**: The `*system*` dynamic var is now in `litellm.system`, not `litellm.core`.

3. **Streaming requires system**: Streaming is no longer available in the system-independent `litellm.core/completion`. Use `litellm.system/completion` instead.

## Benefits of New Structure

1. **Cleaner separation of concerns**: System management is separate from provider operations
2. **Simpler API for basic use**: No system required for simple completion calls
3. **More flexible**: Choose between system-independent and system-based approaches
4. **Better testability**: Provider functions can be tested without system setup
5. **Clearer mental model**: litellm.core focuses on providers, litellm.system on lifecycle

## Backward Compatibility Notes

The old `litellm.core` API is **not backward compatible**. You will need to update your code according to this guide.

However, the migration is straightforward:
- For simple use cases: Remove system management, use `litellm.core/completion` directly
- For advanced use cases: Import `litellm.system` and pass system explicitly

## Questions?

If you encounter issues during migration, please:
1. Check this guide for common patterns
2. Review the updated examples in the `examples/` directory
3. Check the test files for usage examples
4. Open an issue on GitHub if you need help
