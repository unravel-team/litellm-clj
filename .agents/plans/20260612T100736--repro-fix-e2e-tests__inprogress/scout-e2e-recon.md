# Code Context
## Overview
`make e2e` runs the focused E2E suite where tests hit real provider APIs. `test-openai-provider` and `test-anthropic-provider` make unguarded non-streaming calls (`litellm/completion`, `litellm/chat`) expecting success. In your repro, those calls are failing because API accounts are out of funds, not because request/response schema is broken.

OpenAI failure maps to `:litellm/quota-exceeded` on HTTP 429 (`insufficient_quota`). Anthropic low-credit failures hit status 400 and are mapped to `:litellm/invalid-request`, which makes these e2e tests fail before assertions. The e2e harness does not currently treat these provider-billing states as skip conditions.

## Relevant Files
1. `test/e2e/run_e2e_tests_test.clj` (lines 34-78) - `test-function-calling-impl` catches and only logs most exceptions, indicating no hard assertion expectations around provider-side failures.
2. `test/e2e/run_e2e_tests_test.clj` (lines 80-141) - `test-openai-provider`: basic/completion/helpers execute directly; failures bubble as test failures.
3. `test/e2e/run_e2e_tests_test.clj` (lines 143-203) - `test-anthropic-provider`: same pattern as OpenAI, same failure mode.
4. `test/e2e/run_e2e_tests_test.clj` (line 351+, 473+, 493+) - streaming tests already have `try/catch` and already tolerate runtime failures with logging.
5. `tests.edn` (lines 1-12) - e2e suite is explicitly selected by `:focus-meta [:e2e]`; unit suite explicitly skips `:e2e`.
6. `Makefile` (lines 32-36) - `make e2e` runs `clojure -M:test -m kaocha.runner :e2e --reporter kaocha.report/documentation`.
7. `.github/workflows/e2e.yml` (lines 41-50) - CI injects `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` into E2E run; low/bad-credit keys will fail suite.
8. `src/litellm/core.clj` (lines 80-107) - `completion` builds request, validates, dispatches to provider multimethods, and throws directly on provider exceptions.
9. `src/litellm/providers/core.clj` (lines 50-59) - `make-request` dispatch to `:openai` and `:anthropic` implementations.
10. `src/litellm/providers/openai.clj` (lines 108-123, 179-205) - OpenAI error wrapper calls `errors/http-status->error` with provider body/details.
11. `src/litellm/providers/anthropic.clj` (lines 152-168, 252-274) - Anthropic error wrapper same path.
12. `src/litellm/errors.clj` (lines 451-463, 476-536) - `http-status->error` maps status 429 conditionally to quota; status 400 always to `invalid-request`.

## Key Code
- `run_e2e_tests_test`:
  - `(deftest ^:e2e test-openai-provider ...)` lines 80-141
  - `(deftest ^:e2e test-anthropic-provider ...)` lines 143-203
  - `(defn test-function-calling-impl ...)` lines 34-78
- `litellm.core`:
  - `(completion [provider-name model request-map config])` lines 79-107
- `litellm.providers.core`:
  - `(defmethod make-request :openai ...)` lines 54-55
  - `(defmethod make-request :anthropic ...)` lines 57-58
- `litellm.providers.openai`:
  - `(defn handle-error-response ...)` lines 108-123
  - `(defn make-request-impl ...)` lines 179-205
- `litellm.providers.anthropic`:
  - `(defn handle-error-response ...)` lines 152-168
  - `(defn make-request-impl ...)` lines 252-276
- `litellm.errors`:
  - `(defn http-status->error [status provider message ...])` lines 451-463
  - `(defn wrap-http-errors [provider-name f])` lines 476-546

## Architecture
`make e2e` -> `kaocha :e2e` (via `tests.edn`) -> `test/e2e/run_e2e_tests_test.clj` provider tests -> `litellm.core/completion` -> `litellm.providers.core/make-request` multimethod -> provider `make-request-impl` -> `errors/wrap-http-errors` -> `http-status->error` -> thrown `ExceptionInfo` (`ex-data` has `:type`, `:message`, etc.).

## Start Here
Start at `test/e2e/run_e2e_tests_test.clj` (open/anthropic blocks), then `src/litellm/errors.clj` 400/429 mapping, then provider `handle-error-response` functions if you need to adjust classification semantics.

## Root Cause
Primary: external provider billing state, not a request-format bug. `OPENAI_API_KEY` returns `:litellm/quota-exceeded` (429 insufficient_quota). Anthropic low-credit returns 400 and is currently classified as `:litellm/invalid-request` by generic 400 mapping.

## Smallest Safe Fix
1) **Minimal, safest for failing e2e**: Harden E2E assertions for provider-billing failures.
   - Update `test/e2e/run_e2e_tests_test.clj` to wrap OpenAI/Anthropic non-streaming API calls in a shared helper that catches `ExceptionInfo`, checks `ex-data` type/message, and treats:
     - `:litellm/quota-exceeded`
     - `:litellm/invalid-request` with low-credit / insufficient-balance / quota wording
     as skipped (log + continue) instead of failing.
2) Optional semantic tweak: in `src/litellm/errors.clj` `http-status->error`, extend 400 branch to classify low-credit bodies as `quota-exceeded` for clearer taxonomy.

## Focused Tests / Commands to Validate
- `make e2e` (full)
- `clojure -M:test -m kaocha.runner :e2e --reporter kaocha.report/documentation`
- In CI/locally with funded keys: ensure `test-openai-provider` and `test-anthropic-provider` pass.
- In local repro of credit-exhausted keys: expect clear skip logs, no hard failure.

## New/Changed Tests Needed
- If only e2e harness is changed, add/adjust e2e-focused unit-style coverage in `test/e2e` for helper that classifies balance/quota errors (no live-provider assertions needed).
- If changing `http-status->error`, add unit coverage for mapping:
  - `(is (= :litellm/quota-exceeded (:type (ex-data ...)))` for 400 responses with low-credit body)
  - existing 400 invalid-request behavior for non-credit payloads.
