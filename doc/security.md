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

## Workflow dependencies

All actions use full commit IDs. Each reference includes the corresponding release version in a comment.
The remediation checked upstream tag identities through GitHub's public ref API and inspected the action manifests at those commits:

- [Checkout v4.2.2](https://github.com/actions/checkout/tree/11bd71901bbe5b1630ceea73d27597364c9af683)
- [Setup Java v4.7.1](https://github.com/actions/setup-java/tree/c5195efecf7bdfc987ee8bae7a71cb8b11521c00)
- [Setup Clojure 12.5](https://github.com/DeLaGuardo/setup-clojure/tree/bc7570e912b028bbcc22f457adec7fdf98e2f4ed)
- [Cache v4.2.3](https://github.com/actions/cache/tree/5a3ec84eff668545956fd18022155c47e93e2684)
- [Codecov v4.6.0](https://github.com/codecov/codecov-action/tree/b9fd7d16f6d7d1b5d2bec1a2887e65ceed900238)
- [Upload artifact v4.6.2](https://github.com/actions/upload-artifact/tree/ea165f8d65b6e75b540449e92b4886f43607fa02)

Manifest review establishes declared inputs and entry points, not a complete audit of bundled JavaScript or downloaded tools.
Workflows grant only `contents: read`. Checkout does not persist credentials.
Dependabot proposes action updates weekly. Review upstream changes and bundled dependencies before accepting each new commit ID.
Do not replace commit IDs with mutable tags.

The unit workflow uploads to Codecov only for push events on main, on JDK 17.
Only that upload step receives `CODECOV_TOKEN`. Pull-request jobs receive no Codecov token.
Both pull-request and main jobs retain the coverage report as a GitHub artifact for seven days.
Jeeves chose to preserve the existing Codecov service and its history rather than remove it.
The Codecov tag resolves through an annotated tag to the pinned commit. Its manifest was inspected, but its downloaded uploader remains a separate supply-chain boundary.

## Error diagnostics

Library exception constructors remove raw requests, configuration, responses, validation reports, stack traces, and cause text from diagnostic data.
Nested maps and collections redact credential-like keys and all header values.
`error-details` applies this filter again, including to errors created by older callers.
Streaming error chunks use the same filter.

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
