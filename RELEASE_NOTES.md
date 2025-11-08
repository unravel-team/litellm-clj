# v0.3.0-alpha.1
- Add support for Reasoning tokens
- Add support for Embeddings API
- Add support for GPT-5* models
- Updated version references in README.md and build.clj
- Minor documentation corrections

This is a patch release on top of v0.3.0-alpha with version consistency fixes.
---

# v0.3.0-alpha

### 🔧 Function/Tool Calling Support
- **Anthropic Function Calling** - Full tool calling support for Claude models
  - Tool definitions and tool choice configuration
  - Tool result handling
  - E2E tests for function calling
- **OpenRouter Tool Support** - Enhanced tool transformation for multiple schema formats
- **Improved Tool Schema Validation** - Better error handling and validation

### 🏗️ Architecture Improvements
- **Router System Refactor** - Replaced registry pattern with cleaner router-based architecture
  - Simplified API surface
  - Better resource management
  - Improved code organization
- **Removed Claypoole Dependency** - Switched to simpler threadpool management
  - Reduced dependencies
  - More straightforward concurrency model
  - Better error handling

### 🌐 Enhanced Provider Support
- **OpenRouter Streaming** - Full streaming support for OpenRouter provider
  - Registered streaming multimethods
  - Support for multiple response formats
- **Gemini Streaming** - Added streaming support for Google Gemini models
- **Better Error Types** - Introduced structured error types for better debugging

### 📚 Documentation & Examples
- Comprehensive documentation updates across all API guides
- Fixed and improved examples:
  - Tool calling examples with correct API patterns
  - Error handling examples
  - Provider-specific examples
- Updated README with function calling support information
- Removed outdated migration guides and documents

### 🧪 Testing Improvements
- Enhanced E2E test suite with function calling tests
- Added `supports-streaming` flag for better test organization
- Force tool usage in tests with `:tool-choice :required`
- Improved error logging in tests

### 🔨 Bug Fixes & Refinements
- Fixed message schema to allow tool calls
- Fixed OpenRouter tool transformation to handle both schema formats
- Fixed `transform-tool-calls` to return proper vector format
- Corrected docstring syntax errors
- Fixed linting warnings throughout codebase
- Improved provider requires in core.clj

## 🚀 Key Features (Cumulative)

### Unified LLM API
- Single, consistent API across multiple LLM providers
- No need to learn different SDKs for each provider
- Easy switching between providers without code changes

### Provider Support
- **OpenAI** - GPT-3.5-Turbo, GPT-4, GPT-4o with function calling
- **Anthropic** - Claude 3 (Opus, Sonnet, Haiku), Claude 2.x with tool calling 🆕
- **Google Gemini** - Gemini Pro, Gemini Pro Vision, Gemini Ultra with streaming 🆕
- **OpenRouter** - Access to 100+ models with streaming support 🆕
- **Ollama** - Run models locally
- **Mistral** - Mistral models support

### Enterprise Features
- **Router-Based Architecture** - Clean, maintainable code structure 🆕
- **Async Operations** - Non-blocking API calls with core.async
- **Health Monitoring** - Built-in system health checks
- **Cost Tracking** - Token counting and cost estimation
- **Schema Validation** - Request/response validation with Malli
- **Structured Error Types** - Better error handling and debugging 🆕

### Streaming & Function Calling
- Stream responses for better user experience
- OpenAI-style function calling support
- Anthropic tool calling support 🆕
- Async streaming with callback handlers

## 📦 Installation

Add to your `deps.edn`:

```clojure
{:deps {tech.unravel/litellm-clj {:mvn/version "0.3.0-alpha.1"}}}
```

Or with Leiningen:

```clojure
[tech.unravel/litellm-clj "0.3.0-alpha.1"]
```

## 🔧 Quick Start

```clojure
(require '[litellm.core :as litellm])
(require '[litellm.router :as router])

;; Create a router
(def llm-router (router/create-router 
                  {:providers {:openai {:api-key "your-key"}
                              :anthropic {:api-key "your-key"}}}))

;; Make a request
(def response (router/chat llm-router
                {:model "gpt-3.5-turbo"
                 :messages [{:role "user" 
                            :content "Hello!"}]}))

;; With function calling (Anthropic)
(def response (router/chat llm-router
                {:model "claude-3-sonnet-20240229"
                 :messages [{:role "user" :content "What's the weather?"}]
                 :tools [{:type "function"
                          :function {:name "get_weather"
                                   :description "Get weather"
                                   :parameters {:type "object"
                                              :properties {:location {:type "string"}}}}}]
                 :tool-choice :auto}))
```

## � Documentation

- Comprehensive README with examples
- Provider-specific configuration guides
- API reference documentation
- Router API guide
- Function calling examples
- Quick start tutorials

## ⚠️ Breaking Changes

### Router System Refactor
The system/registry pattern has been replaced with a router-based architecture:

**Old Pattern (v0.2.0):**
```clojure
(def system (litellm/create-system {:providers {...}}))
(litellm/make-request system {...})
(litellm/shutdown-system! system)
```

**New Pattern (v0.3.0-alpha):**
```clojure
(def router (router/create-router {:providers {...}}))
(router/chat router {...})
;; No shutdown needed - cleaner resource management
```

### Removed Claypoole Dependency
If you were directly using threadpool features, you'll need to adapt to the new simplified concurrency model.

## 🐛 Known Issues

This is an alpha release with some known limitations:

1. Function calling support is currently available for Anthropic and OpenRouter
2. Some edge cases in tool schema validation may need refinement
3. Integration tests require API keys for all providers
4. Streaming support varies by provider

These will be addressed in the beta and stable releases.

## 🔜 What's Next (v0.3.0 stable planned)

- Function calling support for all providers
- Azure OpenAI support
- Cohere integration
- Enhanced streaming API consistency
- Improved error messages
- Better test coverage
- Performance optimizations
- Batch request support

## 💡 Getting Help

- GitHub Issues: https://github.com/unravel-team/clj-litellm/issues
- Documentation: See README.md
- Examples: Check the `examples/` directory

## 🙏 Acknowledgments

This project is inspired by the Python [LiteLLM](https://github.com/BerriAI/litellm) library. Special thanks to the LiteLLM team for their excellent work.

## 📄 License

MIT License - see LICENSE file for details.

---

**Maven Coordinates:**
```
GroupId: tech.unravel
ArtifactId: litellm-clj  
Version: 0.3.0-alpha.1
```

**Clojars:** https://clojars.org/tech.unravel/litellm-clj

## 📝 Upgrade Notes from v0.2.0

⚠️ **This is an alpha release with breaking changes.**

### Migration Guide

1. **Update Dependencies:**
   ```clojure
   {:deps {tech.unravel/litellm-clj {:mvn/version "0.3.0-alpha.1"}}}
   ```

2. **Update Imports:**
   ```clojure
   ;; Add router namespace
   (require '[litellm.router :as router])
   ```

3. **Migrate to Router Pattern:**
   ```clojure
   ;; Old:
   (def system (litellm/create-system {:providers {...}}))
   
   ;; New:
   (def router (router/create-router {:providers {...}}))
   ```

4. **Update API Calls:**
   ```clojure
   ;; Old:
   (litellm/make-request system {...})
   
   ;; New:
   (router/chat router {...})
   ```

5. **Remove Shutdown Calls:**
   ```clojure
   ;; Old:
   (litellm/shutdown-system! system)
   
   ;; New: Not needed with router pattern
   ```

### New Function Calling Example (Anthropic)

```clojure
(require '[litellm.router :as router])

;; Create router with Anthropic
(def router (router/create-router 
              {:providers {:anthropic {:api-key (System/getenv "ANTHROPIC_API_KEY")}}}))

;; Define tools
(def tools
  [{:type "function"
    :function {:name "get_weather"
               :description "Get current weather for a location"
               :parameters {:type "object"
                          :properties {:location {:type "string"
                                                 :description "City name"}}
                          :required ["location"]}}}])

;; Make request with tools
(def response 
  (router/chat router
    {:model "claude-3-sonnet-20240229"
     :messages [{:role "user" :content "What's the weather in San Francisco?"}]
     :tools tools
     :tool-choice :auto}))
```

---

# Previous Releases

## Release Notes - LiteLLM Clojure v0.2.0

**Release Date:** May 10, 2025

### 🎉 New Release - Google Gemini Support!

We're excited to announce v0.2.0 of LiteLLM Clojure, featuring full integration with Google Gemini models!

### 🆕 What's New in v0.2.0

#### Google Gemini Integration
- **Full Gemini Support** - Native integration with Google's Gemini models
  - Gemini Pro, Gemini Pro Vision, Gemini Ultra
  - Streaming responses
  - Vision/multimodal capabilities
  - Safety settings configuration
  - Advanced generation parameters (temperature, top-k, top-p)

### Provider Support
- **OpenAI** - GPT-3.5-Turbo, GPT-4, GPT-4o with function calling
- **Anthropic** - Claude 3 (Opus, Sonnet, Haiku), Claude 2.x
- **Google Gemini** - Gemini Pro, Gemini Pro Vision, Gemini Ultra 🆕
- **OpenRouter** - Access to 100+ models through a single API
- **Ollama** - Run models locally
