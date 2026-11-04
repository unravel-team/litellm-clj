(require '[clojure.string :as str])

(defn denied! [label f]
  (let [failure (try (f) nil (catch Exception e e))]
    (when-not (and failure
                   (some #(str/includes? (or (.getMessage ^Exception failure) "") %)
                         ["Operation not permitted" "Permission denied"]))
      (throw (ex-info (str label " was not denied by the OS sandbox") {})))
    (println label "denied by OS sandbox")))

(doseq [variable ["OPENAI_API_KEY" "ANTHROPIC_API_KEY" "OPENROUTER_API_KEY"
                  "GEMINI_API_KEY" "AZURE_OPENAI_API_KEY" "MISTRAL_API_KEY"
                  "DEEPSEEK_API_KEY" "MOONSHOT_API_KEY" "KIMI_API_KEY" "ZAI_API_KEY"
                  "AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_SESSION_TOKEN"
                  "CLOJARS_PASSWORD" "CODECOV_TOKEN" "GITHUB_TOKEN" "REM_STORE"]]
  (when (System/getenv variable)
    (throw (ex-info "Unexpected credential variable in verification environment" {:variable variable}))))
(println "Credential variables absent")

(denied! "Loopback socket"
         #(with-open [socket (java.net.Socket.)]
            (.connect socket (java.net.InetSocketAddress. "127.0.0.1" 9) 1000)))
(let [outside (first *command-line-args*)]
  (when-not outside (throw (ex-info "Supply a nonsecret sentinel path outside the workspace" {})))
  (denied! "Read outside disposable home" #(slurp outside))
  (denied! "Write outside disposable workspace" #(spit outside "unexpected-write")))
