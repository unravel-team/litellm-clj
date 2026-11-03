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
