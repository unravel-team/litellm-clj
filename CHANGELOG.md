# Changelog

## 0.4.x (unreleased)

This release contains breaking changes. The minor version increases from `0.3` to `0.4`. The build adds the Git commit count as the final version number.

Breaking: Move `:api-base` from request maps to the explicit configuration argument or a registered router configuration. Completion, streaming, and embedding calls now reject request-level `:api-base`. Configuration values take precedence over request-level `:api-key` and `:timeout`. Configured endpoints must use HTTP or HTTPS with a host and no user information, query string, or fragment.

Breaking: Update code that depends on raw diagnostic content or provider error messages. Errors now redact credentials and raw payloads. HTTP failures report their status instead of provider message text. Network and streaming failures use fixed messages. Logs omit raw stream lines, response headers, request payloads, and external exception messages. Error types and structured status and retry metadata remain available where supplied.

Pass custom endpoints through trusted configuration:

```clojure
(require '[litellm.core :as core])

(core/completion :openai "gpt-4"
                 {:messages [{:role :user :content "Hello"}]}
                 {:api-key "fake-example-key"
                  :api-base "https://trusted-proxy.example/v1"})
```

Only application-controlled configuration can authorize a destination for credentials and content. Do not construct this configuration from untrusted request data. See [Security boundaries](doc/security.md) for endpoint rules, diagnostic behavior, and required credential controls.

Generated audit evidence stays outside version control. Audit and verification commands remain available for local use.

## Earlier releases

See [RELEASE_NOTES.md](RELEASE_NOTES.md) for the earlier release history.
