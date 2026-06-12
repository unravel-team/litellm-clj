# Reviewer final diff

Scope reviewed:
- `test/e2e/run_e2e_tests_test.clj`
- `.agents/plans/20260612T100736--repro-fix-e2e-tests__inprogress/*`

Reviewer source edits: none. Artifact only.

## Blockers

None found for intended E2E failure fix.

Current implementation matches core requirement: OpenAI quota and Anthropic low-credit account states can skip/log, while non-skippable exceptions from top-level OpenAI/Anthropic E2E calls rethrow and still fail.

## What passed review

- `skippable-provider-error?` covers observed account states:
  - `:litellm/quota-exceeded` for OpenAI insufficient quota.
  - `:litellm/invalid-request` only when message has account/credit wording (`credit balance`, `purchase credits`, `insufficient quota`) for Anthropic low-credit style failures.
- `testing-with-skippable-provider-errors` wraps only OpenAI/Anthropic top-level E2E test bodies. It does not broadly change other providers.
- Non-skippable exceptions in wrapped top-level test bodies rethrow via `(throw e#)`, so provider/library errors like auth failures, invalid responses, server/provider errors, and ordinary invalid requests should still fail.
- Missing API key skip behavior remains unchanged.
- Macro form is more maintainable than duplicating `try/catch` around both providers.
- `git diff --check` passed for reviewed paths.
- Progress artifact says fallback kondo gate `make lint` passed with `0 errors, 0 warnings`.

## Fixes worth doing now

1. **Document or narrow `:litellm/rate-limit` skip policy.**
   - Current `skippable-provider-error-types` includes `:litellm/rate-limit`, though task/plan focus is quota and low-credit account state.
   - If transient rate limiting should also be considered untestable live-provider state, add explicit positive coverage for it.
   - If not, remove it so rate-limit failures surface.

2. **Add negative classifier coverage before commit if main agent can still touch tests.**
   - Suggested cases:
     - `:litellm/invalid-request` with normal validation message => false.
     - `:litellm/authentication-error` => false.
     - `:litellm/provider-error` / `:litellm/invalid-response` => false.
     - plain `Exception` => false.
   - This directly guards requirement that non-skippable provider/library errors still fail.

## Optional notes

- `account-state-errors-are-skippable` has no `^:e2e` metadata, so it should run with unit `make test`, not focused `make e2e`. That is acceptable because predicate test is pure and avoids live API calls.
- Existing streaming and function-calling subtests still catch/log all exceptions internally. That predates this fix, but if project wants *all* non-skippable provider/library errors in OpenAI/Anthropic E2E to fail, those inner catch blocks need separate behavior change and tests.
- Skip reporting uses `(is true ...)`, so Kaocha reports pass, not a real skipped test. This matches existing missing-key pattern.

## Validation gaps

- Reviewer did not rerun `make lint`, `make test`, or `make e2e` due review-only/read-only command constraints. Relied on recorded progress plus manual diff review.
- No validation with funded OpenAI/Anthropic keys recorded in reviewer pass. Success path appears unchanged, but live funded-key run remains best confidence.
- No negative tests currently prove non-skippable errors return false from `skippable-provider-error?`.
