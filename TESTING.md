# Testing guide

## Offline gates

Run these commands before submitting a change:

```bash
make lint
make test
make coverage
make test-release
ruby test/security/workflows_test.rb
```

The repository has no `make format` or `make check` target.
Use `make lint` for the existing static gate. Missing targets do not indicate a code failure.
`make test-release` builds a local JAR and checks its POM and embedded source metadata. It does not publish anything.

`make test`, `make test-ci`, and `make coverage` select only Kaocha's `:unit` suite.
They use fake responses and require no provider credentials or local Ollama server.
Do not omit `:unit` from direct Kaocha commands. The default selection also includes the live E2E suite.

```bash
# One namespace
clojure -Srepro -M:test -m kaocha.runner :unit --focus litellm.core-test

# One test
clojure -Srepro -M:test -m kaocha.runner :unit --focus litellm.core-test/test-completion-invalid-request

# Detailed output
make test-ci

# Watch the offline suite
clojure -Srepro -M:test -m kaocha.runner :unit --watch
```

## Writing offline tests

Unit tests live under `test/litellm/` and mirror the source namespaces.
Every unit namespace must install `litellm.offline/guard` as an `:each` fixture:

```clojure
(ns litellm.example-test
  (:require [clojure.test :refer [use-fixtures]]
            [litellm.offline :as offline]))

(use-fixtures :each offline/guard)
```

The guard blocks Hato transport calls. If application code catches the exception, the guard still fails on the attempt.
Replace the provider boundary with a fake for each exercised path. Assert the returned result, error, or observable log output.
Do not make a connection failure the expected result of a unit test.
Use fixed fake API keys, never keys read from the developer's environment.
Router setup tests reject unstubbed environment reads. Supply a fake map through the existing `litellm.router/env` helper.

For a bug, first run a regression that fails for the intended reason.
After the repair, run the focused test and full offline suite.
For stream tests, consume or close the fake stream and use a bounded wait. Do not leave background work running after assertions finish.
The logging tests capture all log levels and check that payload markers and credential markers stay out of output.

Coverage runs the same suite and exclusions as `make test`.
Reports are `target/coverage/index.html` and `target/coverage/codecov.json`.
CI generates coverage on JDK 17. There is no enforced percentage threshold.
Read the actual report rather than relying on historical estimates.

## Disposable macOS verification

Commit the intended source changes first. The verifier exports committed Git `HEAD`, not uncommitted source changes.
It uses Git's read-only archive command because jj has no archive command. It does not create or switch branches.
Run:

```bash
make offline-verification
```

This command requires macOS, `sandbox-exec`, the installed Clojure CLI and JDK, Ruby, and local Git history.
It creates `.verification/run-*`, a disposable source copy, HOME, and temporary directory.
It clears inherited environment variables, copies no personal Maven or Clojure settings, and disables AWS instance metadata.
Git metadata includes source history for the build, but no hooks or original Git settings.

Preparation has network access. It resolves the seven declared public Maven graphs into the empty disposable HOME with `-P`.
It does not run their application entry points. Every resolved JAR must match the host inventory's SHA-256 digest before verification starts.
No provider or Codecov calls belong to this phase.

The verification phase uses the checked-in `scripts/offline-verification.sb` profile.
It denies network operations, reads under `/Users` outside the workspace, and writes outside the workspace except `/dev/null`.
A probe checks that credential variables are absent and that the OS denies a loopback connection and outside sentinel read/write.
The phase runs lint, unit tests, a JAR/provenance check, coverage, optional Bedrock transformation tests, inventories, and workflow policy tests.
All seven regenerated inventories must match the host inventories byte for byte.

The workspace retains preparation/verification logs, initial/final file hashes, file changes, sampled process names, and `result.json`.
The result records the source revision and runner/profile digests.
Process names are sampled every 50 ms. This can miss short-lived processes and is not a complete system-call trace.
File hashes show the before/after state, not every temporary write.
The profile allows system reads outside `/Users`. It is not a VM or proof that every host file is isolated.
Do not use this profile to run unreviewed hostile code.
The credential-free tests do not check provider authentication, AWS credential discovery, or external service behavior.

## Dependency and workflow audits

```bash
make dependency-inventory
make dependency-audit
make actions-audit
```

These are separate from offline tests. The inventory records resolved coordinates, versions, artifact hashes, and dependency relationships.
The audit commands query the public OSV service and require network access.
Action auditing also reads public pinned upstream sources. It does not execute actions or write upstream lockfiles.
Both audits exit nonzero on advisory matches or incomplete results.
The retained action graph has known matches, so its audit is not a passing gate.
See [security boundaries and assessments](doc/security.md) for scope, remaining risks, and owner actions.

## Live tests and CI

`make e2e`, explicitly selected integration tests, and `make test-streaming` can contact real providers or local Ollama.
They can incur charges. Run them only with explicit approval, scoped test credentials, and no production data.
Do not use them as prerequisites for offline unit tests.
The optional Bedrock verifier runs transformations only. It does not exercise the AWS credential chain or invoke Bedrock.

Pull-request CI runs offline tests without provider or Codecov credentials.
The test matrix uses JDK 11, 17, and 21. Local macOS verification uses the selected installed JDK only.
Coverage artifacts remain available for seven days. Codecov uploads run only on main push events on JDK 17.
The Codecov token belongs only to that upload step.
Live provider CI requires manual dispatch on main and approval through the owner's protected `provider-e2e` environment.
The repository owner must configure that environment and rotate previously exposed credentials before enabling it.
See [deployment](DEPLOYMENT.md) for the separate owner-approved release process.
