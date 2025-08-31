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
{:deps {litellm/litellm {:local/root "/path/to/clj-litellm"}}}
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- [LiteLLM](https://github.com/BerriAI/litellm) - The original Python library that inspired this port
