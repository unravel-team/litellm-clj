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

## Credentialed CI

Pull requests run offline unit tests without provider credentials.
The E2E workflow runs only through manual dispatch on main, checks out main, and targets the `provider-e2e` environment.
It does not run on pull requests or automatically after merge.

Before enabling this workflow, the repository owner must create the `provider-e2e` environment.
Require reviewer approval, restrict deployment branches to main, and disallow self-approval.
Store provider credentials only as environment secrets, not repository or organization secrets available to this repository.
Without environment protection, the YAML alone cannot prevent edits that remove its guards.
GitHub can create an unprotected environment when a job references a missing name, so do not add credentials before protection exists.

Use dedicated provider projects with low spending limits, minimum permissions, and no production data.
Review the main revision before approving a run. Run `ruby test/security/workflows_test.rb` for the local workflow policy test.
No credentialed workflow was run during remediation.

If earlier pull-request runs received provider credentials, treat those credentials as potentially exposed.
Revoke them at each provider, review usage and billing, then issue replacement test-only keys.
Remove old repository or organization secrets before storing replacements in the protected environment.
Review retained workflow logs and artifacts for disclosures. Limit access or remove exposed artifacts under the owner's incident process.
Credential rotation and GitHub environment setup require owner action. Local code changes do not perform them.
