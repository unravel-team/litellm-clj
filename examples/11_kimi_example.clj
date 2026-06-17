(ns examples.11-kimi-example
  "Kimi/Moonshot provider examples for litellm-clj."
  (:require [clojure.core.async :as async]
            [clojure.pprint :as pprint]
            [litellm.core :as llm]
            [litellm.router :as router]
            [litellm.streaming :as streaming]))

(defn kimi-api-key []
  (or (System/getenv "MOONSHOT_API_KEY")
      (System/getenv "KIMI_API_KEY")))

(defn setup! []
  (router/setup-kimi!
   :model "kimi-k2.6"
   :api-key (kimi-api-key)))

(defn basic-completion []
  (let [response (llm/kimi-completion
                  "kimi-k2.6"
                  {:messages [{:role :user
                               :content "Give one practical reason to use Clojure for data pipelines."}]
                   :max-tokens 200
                   :thinking {:type :enabled :keep :all}}
                  :api-key (kimi-api-key))]
    (println "Answer:" (llm/extract-content response))
    (when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
      (println "Reasoning:" reasoning))
    (println "Usage:")
    (pprint/pprint (:usage response))))

(defn json-schema-completion []
  (llm/kimi-completion
   "kimi-k2.6"
   {:messages [{:role :user
                :content "Return a tiny project estimate for adding a cache."}]
    :response-format {:type :json-schema
                      :json-schema {:name "estimate"
                                    :schema {:type "object"
                                             :properties {:days {:type "integer"}
                                                          :risk {:type "string"}}
                                             :required ["days" "risk"]}}}
    :max-tokens 300}
   :api-key (kimi-api-key)))

(defn k2-7-code-example []
  ;; Kimi K2.7 Code thinking cannot be disabled. Omit :thinking or use enabled.
  (llm/kimi-completion
   "kimi-k2.7-code"
   {:messages [{:role :user
                :content "Write a small Clojure function that increments a number."}]
    :max-tokens 300
    :thinking {:type :enabled}}
   :api-key (kimi-api-key)))

(defn streaming-reasoning []
  (let [ch (llm/kimi-completion
            "kimi-k2.6"
            {:messages [{:role :user
                         :content "Think briefly, then summarize transducers."}]
             :thinking {:type :enabled}
             :stream true}
            :api-key (kimi-api-key))]
    (async/go-loop []
      (when-let [chunk (async/<! ch)]
        (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning-content])]
          (println "\n[thinking]" reasoning))
        (when-let [content (streaming/extract-content chunk)]
          (print content)
          (flush))
        (recur)))))

(comment
  ;; export MOONSHOT_API_KEY="..." # or KIMI_API_KEY
  (setup!)
  (basic-completion)
  (json-schema-completion)
  (k2-7-code-example)
  (streaming-reasoning))
