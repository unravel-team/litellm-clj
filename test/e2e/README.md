# E2E Provider Tests

This directory contains end-to-end tests that make **real API calls** to each supported LLM provider.

## Overview

Unlike the unit tests in `test/litellm/`, these E2E tests actually call the provider APIs to verify that:
- The provider integration works correctly
- API requests are properly formatted
- Responses are correctly parsed
- Error handling works as expected

## Running E2E Tests

### Quick Start

```bash
make e2e
```

This will run the E2E test suite for all providers. The tests will skip any provider for which API keys are not configured.

### Manual Execution

You can also run the tests directly:

```bash
clojure -M test/e2e/run_e2e_tests.clj
```

## Configuration

The E2E tests require API keys to be set as environment variables:

| Provider   | Environment Variable    | Get API Key From                           |
|------------|------------------------|-------------------------------------------|
| OpenAI     | `OPENAI_API_KEY`       | https://platform.openai.com/api-keys     |
| Anthropic  | `ANTHROPIC_API_KEY`    | https://console.anthropic.com/            |
| Gemini     | `GEMINI_API_KEY`       | https://makersuite.google.com/app/apikey |
| Mistral    | `MISTRAL_API_KEY`      | https://console.mistral.ai/               |
| Ollama     | (no key required)      | https://ollama.ai/ (local server)        |

### Setting API Keys

**For local development:**

```bash
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."
export GEMINI_API_KEY="..."
export MISTRAL_API_KEY="..."
```

**For GitHub Actions:**

Set these as repository secrets in Settings → Secrets and variables → Actions.

## What Gets Tested

For each configured provider, the E2E tests verify:

1. **Basic Completion**: Simple completion request with minimal parameters
2. **Temperature Control**: Parameter handling (temperature, max_tokens, etc.)
3. **Helper Functions**: The `litellm/chat` convenience function
4. **Streaming**: Streaming completion requests and chunk reception

### Test Output

The tests provide colored output showing:
- ✅ Passed tests
- ❌ Failed tests  
- ⚠️  Skipped tests (missing API key or unavailable service)

Example:

```
╔═══════════════════════════════════════════════════════════╗
║          LiteLLM-Clj E2E Provider Tests                  ║
╚═══════════════════════════════════════════════════════════╝

🧪 Testing openai provider...
  ✓ Basic completion test passed
  ✓ Temperature parameter test passed
  ✓ Helper function test passed
✅ openai provider tests passed!

⚠️  Skipping anthropic - ANTHROPIC_API_KEY not set

╔═══════════════════════════════════════════════════════════╗
║                     Test Summary                          ║
╚═══════════════════════════════════════════════════════════╝
Total providers tested: 5
  ✅ Passed:  1
  ❌ Failed:  0
  ⚠️  Skipped: 4

✅ All configured providers passed!
```

## Cost Considerations

⚠️ **These tests make real API calls which may incur costs!**

The tests are designed to be cost-effective:
- Uses smallest/cheapest models available for each provider
- Limits max_tokens to 10-20 per request
- Runs ~3 requests per provider
- Total cost per full run: typically < $0.10

### Estimated Costs Per Provider

- OpenAI (gpt-3.5-turbo): ~$0.001 per test run
- Anthropic (claude-3-haiku): ~$0.001 per test run
- Gemini (gemini-1.5-flash): Free tier available
- Mistral (mistral-small): ~$0.001 per test run
- Ollama: Free (local)

## CI/CD Integration

The E2E tests are integrated into the GitHub Actions workflow:

- `.github/workflows/e2e.yml` - Runs on PRs and manual triggers
- Only tests OpenAI by default (set `OPENAI_API_KEY` in repo secrets)
- Other providers tested if their API keys are configured

## Troubleshooting

### "Skipping all providers"

Make sure at least one API key environment variable is set:

```bash
echo $OPENAI_API_KEY
# Should output your API key, not blank
```

### "Rate limit exceeded"

If you hit rate limits during testing:
1. Wait a few minutes before retrying
2. For OpenAI, check https://platform.openai.com/account/rate-limits
3. Consider upgrading to a paid tier for higher limits

### "Connection timeout"

For Ollama:
1. Make sure Ollama is running: `ollama serve`
2. Verify the model is pulled: `ollama pull llama2`
3. Check the server is accessible: `curl http://localhost:11434/api/version`

## Adding New Provider Tests

To add E2E tests for a new provider:

1. Add test case to `test/e2e/run_e2e_tests.clj`:

```clojure
(test-provider :newprovider "model-name" "NEWPROVIDER_API_KEY")
```

2. Update this README with the new provider information

3. Test locally before committing:

```bash
export NEWPROVIDER_API_KEY="..."
make e2e
```

## Best Practices

1. **Don't commit API keys** - Use environment variables only
2. **Run locally first** - Verify tests pass before pushing
3. **Monitor costs** - Check your provider dashboards regularly
4. **Use test accounts** - Use separate API keys for testing if available
5. **Respect rate limits** - Don't run tests too frequently

## Questions?

- Check the main [TESTING.md](../../TESTING.md) guide
- See examples in `examples/` directory
- Open an issue on GitHub
