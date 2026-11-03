# Testing Guide

This document describes the testing setup and best practices for the litellm-clj project.

## Offline and live boundaries

`make test`, `make test-ci`, and `make coverage` run only the unit suite.
The tests use fake provider responses and require no API keys.
Every unit namespace installs `litellm.offline/guard` as an `:each` fixture.
The guard blocks Hato transport calls and fails on attempts, even if a test catches the exception.
New unit namespaces must install this fixture too.
Coverage uses the same Kaocha suite and exclusions as unit tests.

`make e2e`, integration tests, and `make test-streaming` contact real providers or local Ollama.
These commands can incur charges. Run them only with explicit approval and scoped test credentials.
Do not use production credentials. Unit tests must not depend on these commands.

The repository has no `make format` or `make check` target.
Use `make lint` for the existing static gate. Missing targets do not indicate a code failure.

## Test Structure

Tests are organized in the `test/` directory, mirroring the `src/` structure:

```
test/
├── litellm/
│   ├── config_test.clj          # Configuration tests
│   ├── core_test.clj             # Core API tests
│   ├── streaming_test.clj        # Streaming tests
│   └── providers/
│       ├── openai_test.clj       # OpenAI provider tests
│       ├── anthropic_test.clj    # Anthropic provider tests
│       ├── gemini_test.clj       # Gemini provider tests
│       ├── mistral_test.clj      # Mistral provider tests
│       └── ollama_test.clj       # Ollama provider tests
```

## Running Tests

### Run All Tests

```bash
# Using make
make test

# Using Clojure CLI directly
clojure -M:test -m kaocha.runner
```

### Run Tests with Verbose Output

```bash
make test-ci
```

### Run Specific Test Namespace

```bash
clojure -M:test -m kaocha.runner --focus litellm.config-test
```

### Watch Mode (Re-run on file changes)

```bash
clojure -M:test -m kaocha.runner --watch
```

## Code Coverage

### Generate Coverage Report

```bash
make coverage
```

This generates a coverage report in `target/coverage/` including:
- HTML report: `target/coverage/index.html`
- Codecov JSON: `target/coverage/codecov.json`

### Coverage Threshold

The project aims for **75%+ code coverage**. Coverage is automatically checked in CI on every commit.

## Linting

### Run Linter

```bash
make lint
```

### Lint Configuration

Linter rules are configured in `.clj-kondo/config.edn`. Key checks include:
- Unused namespaces
- Unused bindings
- Type mismatches
- Unresolved symbols

## Continuous Integration

### GitHub Actions Workflows

#### Test Workflow (`.github/workflows/test.yml`)

Runs on every push to `main` and all pull requests:
- Tests against JDK 11, 17, and 21
- Generates coverage report (JDK 17 only)
- Uploads coverage to Codecov

#### Lint Workflow (`.github/workflows/lint.yml`)

Runs clj-kondo linting on every push and pull request.

### CI Commands

The CI uses these commands:
```bash
clojure -P -M:test                    # Download dependencies
clojure -M:test -m kaocha.runner      # Run tests
clojure -M:test:coverage              # Generate coverage
```

## Writing Tests

### Test Structure

```clojure
(ns my-namespace-test
  (:require [clojure.test :refer [deftest testing is]]
            [my-namespace :as sut]))

(deftest test-feature
  (testing "Feature does X"
    (is (= expected (sut/function input)))))
```

### Testing Best Practices

1. **Unit Tests**: Test individual functions in isolation
2. **Integration Tests**: Tag with `^:integration` metadata
3. **Mocking**: Use `mockery` library for mocking HTTP calls
4. **Fixtures**: Use `use-fixtures` for setup/teardown
5. **Descriptive Names**: Use clear, descriptive test names

### Example: Unit Test

```clojure
(deftest test-transform-messages
  (testing "Transform messages to OpenAI format"
    (let [messages [{:role :user :content "Hello"}]]
      (is (= "user" (:role (first (transform-messages messages))))))))
```

### Example: Integration Test

```clojure
(deftest ^:integration test-openai-completion
  (testing "OpenAI completion works with API key"
    (when (System/getenv "OPENAI_API_KEY")
      (let [response (completion :openai "gpt-3.5-turbo" 
                                {:messages [{:role :user :content "test"}]})]
        (is (map? response))
        (is (contains? response :choices))))))
```

### Example: Test with Fixtures

```clojure
(defn setup-router [f]
  (config/clear-registry!)
  (f)
  (config/clear-registry!))

(use-fixtures :each setup-router)
```

## Test Coverage by Module

Current coverage status:

| Module | Coverage | Status |
|--------|----------|--------|
| litellm.config | ~90% | ✅ Good |
| litellm.core | ~60% | ⚠️ Needs improvement |
| litellm.streaming | ~70% | ⚠️ Needs improvement |
| litellm.providers.openai | ~80% | ✅ Good |
| litellm.providers.* | Varies | 🔄 In progress |

## Running Tests Locally Before Pushing

Before pushing code, run:

```bash
make test        # Run all tests
make lint        # Check for linting issues
make coverage    # Verify coverage hasn't dropped
```

## Debugging Failed Tests

### Verbose Output

```bash
clojure -M:test -m kaocha.runner --reporter kaocha.report/documentation
```

### Run Single Test

```bash
clojure -M:test -m kaocha.runner --focus litellm.config-test/test-register-simple-config
```

### Enable Test Output Capture

```bash
clojure -M:test -m kaocha.runner --no-capture-output
```

## Common Issues

### Tests Pass Locally But Fail in CI

- Check JDK version compatibility
- Verify no hardcoded paths
- Ensure environment variables are set correctly

### Coverage Drops Unexpectedly

- Check if new code is untested
- Verify test fixtures are cleaning up properly
- Run coverage locally to debug

### Slow Tests

- Use mocking for external API calls
- Avoid unnecessary I/O operations
- Consider marking slow tests as `^:integration`

## Contributing Tests

When contributing:

1. **Write tests first** (TDD approach encouraged)
2. **Maintain or improve coverage** - don't drop below 70%
3. **Add integration tests** for new providers
4. **Update this guide** if adding new test patterns

## Test Dependencies

Key testing libraries:

- **Kaocha**: Test runner with watch mode and plugins
- **clojure.test**: Core testing framework
- **test.check**: Property-based testing
- **mockery**: HTTP mocking
- **cloverage**: Code coverage
- **clj-kondo**: Static analysis and linting

## Resources

- [Kaocha Documentation](https://cljdoc.org/d/lambdaisland/kaocha/)
- [Clojure Testing Guide](https://clojure.org/guides/test_check_beginner)
- [clj-kondo Configuration](https://github.com/clj-kondo/clj-kondo/blob/master/doc/config.md)
