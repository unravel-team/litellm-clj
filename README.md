# LiteLLM Clojure

A Clojure port of the popular [LiteLLM](https://github.com/BerriAI/litellm) library, providing a unified interface for multiple LLM providers with comprehensive observability and thread pool management.

## Features

- **Unified API**: Single interface for multiple LLM providers
- **Async Operations**: Non-blocking API calls with proper context propagation
- **Provider Abstraction**: Easy to add new LLM providers
- **Health Monitoring**: System health checks and metrics

## Currently Supported Providers

- **OpenAI**: GPT-3.5, GPT-4, and other OpenAI models

## Planned Providers

- Anthropic (Claude)
- Azure OpenAI
- Google (PaLM, Gemini)
- Cohere
- Hugging Face
- And more...

## Installation

Add to your `deps.edn`:

```clojure
{:deps {litellm/litellm {:local/root "/path/to/clj-litellm"}}}
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- [LiteLLM](https://github.com/BerriAI/litellm) - The original Python library that inspired this port

