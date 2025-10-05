# Release Notes - LiteLLM Clojure v0.2.0

**Release Date:** May 10, 2025

## 🎉 New Release - Google Gemini Support!

We're excited to announce v0.2.0 of LiteLLM Clojure, featuring full integration with Google Gemini models!

## 🆕 What's New in v0.2.0

### Google Gemini Integration
- **Full Gemini Support** - Native integration with Google's Gemini models
  - Gemini Pro, Gemini Pro Vision, Gemini Ultra
  - Streaming responses
  - Vision/multimodal capabilities
  - Safety settings configuration
  - Advanced generation parameters (temperature, top-k, top-p)

## 🚀 Key Features

### Unified LLM API
- Single, consistent API across multiple LLM providers
- No need to learn different SDKs for each provider
- Easy switching between providers without code changes

### Provider Support
- **OpenAI** - GPT-3.5-Turbo, GPT-4, GPT-4o with function calling
- **Anthropic** - Claude 3 (Opus, Sonnet, Haiku), Claude 2.x
- **Google Gemini** - Gemini Pro, Gemini Pro Vision, Gemini Ultra 🆕
- **OpenRouter** - Access to 100+ models through a single API
- **Ollama** - Run models locally

### Enterprise Features
- **Thread Pool Management** - Efficient resource utilization with Claypoole
- **Async Operations** - Non-blocking API calls with core.async
- **Health Monitoring** - Built-in system health checks
- **Cost Tracking** - Token counting and cost estimation
- **Schema Validation** - Request/response validation with Malli
- **Caching Support** - Built-in response caching capabilities

### Streaming & Function Calling
- Stream responses for better user experience
- OpenAI-style function calling support
- Async streaming with callback handlers

## 📦 Installation

Add to your `deps.edn`:

```clojure
{:deps {tech.unravel/litellm-clj {:mvn/version "0.2.0"}}}
```

Or with Leiningen:

```clojure
[tech.unravel/litellm-clj "0.2.0"]
```

## 🔧 Quick Start

```clojure
(require '[litellm.core :as litellm])

;; Create a system
(def system (litellm/create-system 
              {:providers {"openai" {:provider :openai
                                     :api-key "your-key"}}}))

;; Make a request
(def response (litellm/make-request system
                {:model "gpt-3.5-turbo"
                 :messages [{:role "user" 
                            :content "Hello!"}]}))

;; Cleanup
(litellm/shutdown-system! system)
```

## 📚 Documentation

- Comprehensive README with examples
- Provider-specific configuration guides
- API reference documentation
- Quick start tutorials

## 🐛 Known Issues

This is an initial release with some known limitations:

1. Test suite needs refinement for edge cases
2. Some advanced streaming features are partial
3. Integration tests require API keys
4. Provider name extraction needs improvement for some edge cases

These will be addressed in upcoming releases.

## 🔜 What's Next (v0.3.0 planned)

- Azure OpenAI support
- Cohere integration
- Enhanced streaming API
- Improved error handling
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
Version: 0.2.0
```

**Clojars:** https://clojars.org/tech.unravel/litellm-clj

## 📝 Upgrade Notes from v0.1.0

This is a minor version release with no breaking changes. Simply update the version number in your `deps.edn`:

```clojure
{:deps {tech.unravel/litellm-clj {:mvn/version "0.2.0"}}}
```

### New Gemini Usage Example

```clojure
;; Configure Gemini
(def system (litellm/create-system 
              {:providers {"gemini" {:provider :gemini
                                     :api-key (System/getenv "GEMINI_API_KEY")}}}))

;; Make a request
(litellm/make-request system
  {:model "gemini-pro"
   :messages [{:role "user" :content "Explain quantum computing"}]})
```
