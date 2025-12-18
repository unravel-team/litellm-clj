# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Azure OpenAI Support** - Full integration with Azure OpenAI Service
  - Chat completions with deployment-based routing
  - Streaming responses support
  - Function calling / Tools support
  - Embeddings support
  - `setup-azure!` for quick configuration
  - Environment variable support: `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_API_BASE`, `AZURE_OPENAI_DEPLOYMENT`
  - Automatic setup via `quick-setup!` when Azure env vars are present

## [0.2.0] - 2025-05-10

### Added
- **Google Gemini Support** - Full integration with Google Gemini models
  - Gemini Pro, Gemini Pro Vision, and Gemini Ultra support
  - Streaming responses support
  - Vision/multimodal capabilities
  - Safety settings configuration
  - Generation parameters (temperature, top-k, top-p)
- Gemini provider implementation with comprehensive error handling
- Gemini-specific test suite
- Gemini usage examples

### Changed
- Updated provider support matrix to show Gemini as ✅ Supported
- Enhanced documentation with Gemini configuration and examples

## [0.1.0] - 2025-05-10

### Added
- Initial release of LiteLLM Clojure
- Unified API for multiple LLM providers
- Support for OpenAI (GPT-3.5-Turbo, GPT-4, GPT-4o)
- Support for Anthropic (Claude 3 Opus, Sonnet, Haiku, Claude 2.x)
- Support for OpenRouter (access to multiple providers)
- Support for Ollama (local models)
- Async operation support with proper thread pool management
- Streaming response support for OpenAI and Anthropic
- Function calling support for OpenAI models
- Provider abstraction layer for easy extension
- Health monitoring and system checks
- Cost tracking and token estimation
- Request/response schema validation with Malli
- Comprehensive error handling
- Thread pool management with Claypoole
- Configuration system with Aero support
- Built-in caching support

### Core API
- `create-system` - Create and configure LiteLLM system
- `shutdown-system!` - Gracefully shutdown system
- `completion` - Main completion API
- `make-request` - Direct request API
- `health-check` - System health monitoring
- `system-info` - Get system information

### Provider Support Matrix
- ✅ OpenAI - Full support with streaming and function calling
- ✅ Anthropic - Full support with streaming
- ✅ OpenRouter - Full support with multiple model access
- ✅ Ollama - Basic support for local models
- 🔄 Azure OpenAI - Planned
- 🔄 Google (Gemini) - Planned
- 🔄 Cohere - Planned
- 🔄 Mistral - Planned

### Known Issues
- Test suite needs refinement for configuration validation
- Provider name extraction logic needs improvement for some edge cases
- Integration tests require API keys and are tagged separately
- Streaming API implementation is partial
- Some advanced OpenAI features not yet supported

### Documentation
- Comprehensive README with installation and usage examples
- Provider-specific configuration guides
- API reference examples
- Quick start guide

### Dependencies
- Clojure 1.11.1
- Hato 0.9.0 (HTTP client)
- Cheshire 5.12.0 (JSON)
- Claypoole 1.1.4 (Thread pools)
- Malli 0.13.0 (Schema validation)
- Core.async 1.6.681 (Async operations)

### Build & Distribution
- Maven/Clojars compatible build system
- tools.build integration
- Group ID: `tech.unravel`
- Artifact ID: `litellm-clj`

[0.1.0]: https://github.com/unravel-team/clj-litellm/releases/tag/v0.1.0
