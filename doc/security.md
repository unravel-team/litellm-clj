# Security boundaries

## Endpoint configuration

Request maps are data, not trusted transport configuration.
`core/completion` and `core/embedding` reject request-level `:api-base`, including streaming calls.
The router passes registered credentials separately from request data.
Trusted configuration takes precedence over request-level keys and timeouts.

This intentionally removes request-level endpoint selection. Pass custom destinations through the explicit configuration argument or a registered router configuration:

```clojure
(core/completion :openai "gpt-4"
                 {:messages [{:role :user :content "Hello"}]}
                 {:api-key "fake-example-key"
                  :api-base "https://trusted-proxy.example/v1"})
```

A configured endpoint is an explicit authorization to send credentials and content to that destination.
Do not build this configuration from untrusted request data.
Only HTTP or HTTPS URLs with a host are accepted. User information, query strings, and fragments are rejected.
Use HTTPS for remote providers. HTTP remains available for explicitly configured local Ollama or development proxies.
The library does not provide a network sandbox or a private-address block list.
Applications must restrict configuration access and enforce their own network policy.

Hato 0.9.0 uses the JDK HTTP client with redirect policy `:never` by default.
Provider requests do not supply an alternate client or redirect policy.
Redirect responses therefore do not authorize forwarding credentials to another endpoint.
Low-level provider functions accept trusted configuration directly and are not an untrusted request API.

## Dependency provenance

All declared Maven dependencies use exact versions, including antq 2.11.1276.
The [Clojars artifact metadata](https://clojars.org/api/artifacts/com.github.liquidz/antq) identifies that antq release.
Make and CI use `-Srepro` to exclude personal Clojure configuration.
CI does not restore dependency caches. Fresh runners resolve the declared graph.

Run `make dependency-inventory` after dependency resolution.
The command writes tab-separated inventories under `target/security/` for runtime, tests, coverage, build, Bedrock, antq, and clj-kondo.
Each inventory records the resolved Maven coordinate, version, artifact filename, SHA-256 digest, and dependent coordinates.
This is a checksummed resolved graph, not a claim that direct version pins lock every upstream POM.
The inventory command rejects mutable versions and snapshots.
It reads the CLI's actual basis and omits machine-local paths and repository credentials.

Regenerate inventories on a clean machine with the same CLI and JDK.
Compare the inventories byte for byte before a release.
If a version, graph, or digest changes, investigate the source before building.
Archive the inventories with release artifacts and their source revision.
A digest from one machine is evidence for comparison, not an independent upstream signature.

`make dependency-audit` also queries the public [OSV API](https://osv.dev/docs/).
It sends only public Maven coordinates and versions, not source or credentials.
It saves the complete result in `target/security/osv.json` and fails on advisory matches or incomplete responses.
It is a networked audit command, not an offline test.
An advisory match requires a reachability assessment or an upgrade. No match does not prove absence of vulnerabilities.

The initial audit covered 217 distinct package versions and found matches for 19.
Generated audit reports stay outside version control.
Runtime matches include Logback 1.4.11, Jackson Core 2.15.2, and Clojure 1.11.1.
Build and optional tooling also resolve old Jackson Databind, AWS SDK v1, Ion, jsoup, Jetty, Guava, Commons, and Plexus versions.
Follow-up issue `.10` upgraded runtime Clojure to 1.12.6, Logback to 1.5.38, and Cheshire to 6.2.1 with Jackson 2.21.8.
It upgraded tools.build, deps-deploy, clj-kondo, and Cognitect AWS API.
Build-only pins patch the deploy tool's AWS SDK v1, Jackson, jsoup, and logging chain.
The antq alias uses current tools.deps to remove the older Jetty, Plexus, and Commons chain.
The clj-kondo alias uses the patched Cheshire parser. Tool aliases explicitly pin Clojure rather than inherit the CLI's bundled version.
No S3 deploy capability was removed. The deploy tool's redundant SLF4J no-op backend is excluded in favor of the patched Logback backend.

The final audit covered 190 distinct package versions with zero OSV matches.
Run `make dependency-audit` to produce a current report in `target/security/osv.json`.
This result covers the seven inventoried graphs, not every optional REPL or developer alias, native executable, or GitHub Action bundle.
It is a time-specific database result, not proof of exploit resistance.
The existing fake-provider suite and local JAR build passed after the upgrades.
The audit fails before the dependency repair and passes afterward.

Representative advisory sources:

- [Clojure deserialization](https://osv.dev/vulnerability/GHSA-vr64-r9qj-h27f)
- [Logback receivers](https://osv.dev/vulnerability/GHSA-vmq6-5m68-f53m)
- [Jackson parser limits](https://osv.dev/vulnerability/GHSA-7hhh-6rmp-j9qf)
- [Plexus extraction](https://osv.dev/vulnerability/GHSA-6fmv-xxpf-w3cw)
- [jsoup XML parsing](https://osv.dev/vulnerability/GHSA-65r4-943x-97jj)

## Workflow dependencies

All actions use full commit IDs. Each reference includes the release version in a comment.
Upstream release and tag APIs identify these commits:

- [Checkout v7.0.1](https://github.com/actions/checkout/tree/3d3c42e5aac5ba805825da76410c181273ba90b1)
- [Setup Java v6.1.0](https://github.com/actions/setup-java/tree/b843ebf9b913146b38da2eaa7aeddf76074ae1ad)
- [Setup Clojure 13.7.0](https://github.com/DeLaGuardo/setup-clojure/tree/6d46099eae24853c33c0482f2bb54edf4f1a4c74)
- [Upload artifact v7.0.2](https://github.com/actions/upload-artifact/tree/cf430e030ddbb5b0abf93d22962f4752f3646cd9)
- [Codecov v4.6.0](https://github.com/codecov/codecov-action/tree/b9fd7d16f6d7d1b5d2bec1a2887e65ceed900238)

Workflows grant only `contents: read`. Checkout does not persist credentials.
Dependabot proposes action updates weekly. Review upstream changes and dependencies before accepting each new commit ID.
Do not replace commit IDs with mutable tags.

Run `make actions-audit` to read the pinned manifests, entry-point bundles, and upstream npm lockfiles.
It records manifest, bundle, and lockfile SHA-256 digests and queries OSV for runtime npm package versions.
Lockfiles stay in memory. The command does not install packages, run action code, or modify lockfiles.
It writes `target/security/actions.json` and exits nonzero on advisory matches or incomplete responses.
Composite and Docker actions need a separate audit. This command refuses them.

The initial audit matched 34 of 236 runtime npm package versions.
After upgrading four actions, the follow-up audit matched 23 of 217.
Run `make actions-audit` to produce a current report in `target/security/actions.json`.
**The GitHub Actions dependency audit is not clean.**
Even the four current upstream releases retain advisory matches.
A source lockfile graph can include code absent from the built bundle. A bundle digest does not prove that code is safe.
This review is not a complete audit of bundled JavaScript, downloaded JDKs, the Clojure installer, or the Codecov CLI.

### Codecov token boundary and remaining matches

The unit workflow uploads to Codecov only for push events on main, on JDK 17.
Only that step receives `CODECOV_TOKEN`. Pull-request jobs receive no Codecov token.
Both pull-request and main jobs retain coverage as a GitHub artifact for seven days.
Artifact retention runs before the Codecov step, so an upload failure does not discard that fallback.
The upload step has a five-minute limit, disables report search and OIDC, and specifies only `target/coverage/codecov.json`.
It pins the downloaded CLI to `v11.3.2`. That release still exposes the three commands used by the v4 wrapper.
CLI checksum or GPG signature failures stop the action because `fail_ci_if_error` is true.

Jeeves chose to keep the Codecov service, its history, and the reviewed v4 Node wrapper.
The current v7 composite wrapper [exports its token through GITHUB_ENV](https://github.com/codecov/codecov-action/blob/303a32d7a59b442fa8d48b6a1cc6825c09c847a5/action.yml).
This exports the token to later job steps.
The v4 [argument builder](https://github.com/codecov/codecov-action/blob/b9fd7d16f6d7d1b5d2bec1a2887e65ceed900238/src/buildExec.ts) passes it to CLI child processes instead.
Do not upgrade this wrapper without checking the token boundary again.

The v4 graph has six advisory-matched package versions. The assessment is specific to the workflow above:

- `@octokit/plugin-paginate-rest 9.0.0`, `@octokit/request 8.1.4`, and `@octokit/request-error 5.0.1` have regular-expression denial-of-service advisories.
  The wrapper uses `github.context` to read local event data. It does not create an Octokit client or make pagination/API calls.
  The vulnerable response/header parsing paths are not used by the reviewed caller.
  See [pagination](https://osv.dev/vulnerability/GHSA-h5c3-5r3r-rr8q), [request](https://osv.dev/vulnerability/GHSA-rmvr-2pp2-xj38), and [request error](https://osv.dev/vulnerability/GHSA-xx4v-prfh-6cgc).
- `@fastify/busboy 2.1.1` has multipart header parsing advisories.
  The wrapper reads checksum and signature responses as text, not multipart form data. It does not use parsed upload filenames.
  See [multipart injection](https://osv.dev/vulnerability/GHSA-gxm5-99cw-xjw9).
- `uuid 8.3.2` has a buffer-bounds advisory for v3/v5/v6 calls.
  The reviewed wrapper does not call those methods with caller-provided output buffers.
  See [UUID buffer bounds](https://osv.dev/vulnerability/GHSA-w5hq-g745-h8pq).
- `undici 5.28.4` is directly reachable in [CLI verification](https://github.com/codecov/codecov-action/blob/b9fd7d16f6d7d1b5d2bec1a2887e65ceed900238/src/validate.ts).
  It makes GET requests for the checksum and signature at fixed `https://cli.codecov.io/v11.3.2/linux/` paths.
  The caller uses no multipart body, caller-supplied headers, WebSocket, cookie forwarding, retry interceptor, custom TLS callback, or HTTP proxy server.
  Those feature-specific advisory paths are absent. This does **not** make every Undici match unreachable.
  A compromised trusted HTTPS endpoint can still cause client denial of service through connection reuse or TLS renegotiation.
  See [response queue poisoning](https://osv.dev/vulnerability/GHSA-35p6-xmwp-9g52) and [TLS renegotiation](https://osv.dev/vulnerability/GHSA-qrxm-fqhp-jwvm).

The reviewed [entry point](https://github.com/codecov/codecov-action/blob/b9fd7d16f6d7d1b5d2bec1a2887e65ceed900238/src/index.ts) downloads the CLI through Node HTTPS, then verifies it before execution.
The signed CLI is a separate executable and trust boundary, not covered by the npm graph audit.
No Codecov action or uploader was executed locally during remediation.

### Other action matches

The four upgraded actions use Node 24. Their remaining matches are recorded without suppression:

- Setup Clojure: `brace-expansion 1.1.14`, `minimatch 3.1.5`, `undici 6.25.0`.
- Checkout: `undici 6.27.0`.
- Setup Java: `minimatch 10.2.6`, `undici 6.29.0`.
- Upload artifact: brace-expansion, minimatch, glob, lodash, XML parser/builder, and Undici versions recorded by `make actions-audit`.

Workflow inputs use fixed tool versions, fixed artifact paths, and a fixed Java matrix, not pull-request titles or caller-supplied patterns.
That limits attacker control over glob and parser inputs, but is not proof that every transitive path is unreachable.
Download and artifact services remain trusted network peers. Server-triggered client denial of service remains possible.
Pull-request jobs have no provider or Codecov credentials. Their job token is read-only and is not persisted by checkout.
The manual provider E2E job is a separate, human-approved credential boundary described below.

Keep these upstream findings visible. Re-run the audit for each proposed action update.
When a reviewed upstream release fixes these findings, replace the affected versions.
Do not describe the Maven audit's zero matches as a result for GitHub Actions.

## Logs

Default logs omit prompts, tools, response bodies, response headers, raw stream lines, and exception messages from external operations.
Parse failures retain the line length and exception class. Streaming diagnostics retain status, model, provider, and chunk counts where available.
This applies at debug level too. Changing the log level does not authorize payload logging.
The tests capture every log level and consume fake streams to completion.
Application callbacks still receive content. The application owns any logging in those callbacks.

## Error diagnostics

Library exception constructors remove raw requests, configuration, responses, validation reports, stack traces, and cause text from diagnostic data.
Nested maps and collections redact credential-like keys and all header values.
`error-details` applies this filter again, including to errors created by older callers.
Streaming error chunks use the same filter. Transport failures use fixed text rather than external exception messages.
Fallback aggregates, retry logs, and provider test diagnostics retain exception classes, not external message text.
The HTTP wrapper preserves typed library errors and their retry/status metadata, including errors wrapped by futures.

HTTP errors now report the status instead of provider-supplied message text. Network errors use fixed messages.
This intentionally changes diagnostic output. Error types, retry classification, status, provider codes, and request IDs remain available.
Do not put secrets in application-defined error messages or metadata. No filter can identify every arbitrary secret in free-form text.
Log `error-summary` or selected metadata, not raw request maps, `ex-data`, or exception causes from external libraries.

## Credentialed CI

Pull requests run offline unit tests without provider credentials.
The E2E workflow runs only through manual dispatch on main, checks out main, and targets the `provider-e2e` environment.
It does not run on pull requests or automatically after merge.

Before enabling this workflow, the repository owner must create the `provider-e2e` environment.
Require reviewer approval, restrict deployment branches to main, and disallow self-approval.
Store provider credentials only as environment secrets, not repository or organization secrets available to this repository.
Without environment protection, the YAML alone cannot prevent edits that remove its guards.
If a job references a missing environment, GitHub can create it without protection. Do not add credentials before protection exists.

Use dedicated provider projects with low spending limits, minimum permissions, and no production data.
Review the main revision before approving a run. Run `ruby test/security/workflows_test.rb` for the local workflow policy test.
No credentialed workflow was run during remediation.

If earlier pull-request runs received provider credentials, treat those credentials as potentially exposed.
Revoke them at each provider, review usage and billing, then issue replacement test-only keys.
Remove old repository or organization secrets before storing replacements in the protected environment.
Review retained workflow logs and artifacts for disclosures. Limit access or remove exposed artifacts under the owner's incident process.
Credential rotation and GitHub environment setup require owner action. Local code changes do not perform them.

## Local verification

Run `make offline-verification` to test committed source in a disposable macOS workspace.
The runner stores local evidence under `.verification/`. Audit commands write reports under `target/security/`.
These directories and the former evidence directory, `doc/security/`, stay outside version control.
If a release requires evidence, archive it separately with its source revision.

The remediation run passed its offline gates. The separate action advisory audit retained known matches and was not clean.
Real providers, hosted CI, credential rotation, environment protection, and publication were not exercised.
