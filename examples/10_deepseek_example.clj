(ns examples.10-deepseek-example
  "DeepSeek provider examples for litellm-clj."
  (:require [clojure.core.async :as async]
            [clojure.pprint :as pprint]
            [litellm.core :as llm]
            [litellm.router :as router]
            [litellm.streaming :as streaming]))

(def default-model "deepseek-v4-pro")
(def flash-model "deepseek-v4-flash")

(defn deepseek-api-key []
  (System/getenv "DEEPSEEK_API_KEY"))

(defn setup! []
  (router/setup-deepseek!
   :model default-model
   :api-key (deepseek-api-key)))

(defn basic-completion []
  (let [response (llm/deepseek-completion
                  default-model
                  {:messages [{:role :user
                               :content "Explain why Clojure maps are useful in two sentences."}]
                   :max-tokens 200
                   :reasoning-effort :high}
                  :api-key (deepseek-api-key))]
    (println "Answer:" (llm/extract-content response))
    (when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
      (println "Reasoning:" reasoning))
    (println "Usage:")
    (pprint/pprint (:usage response))))

(defn json-mode-completion []
  (llm/deepseek-completion
   flash-model
   {:messages [{:role :system
                :content "Return only valid JSON."}
               {:role :user
                :content "Create a JSON object with keys language and strength for Clojure."}]
    :response-format {:type :json-object}
    :max-tokens 200}
   :api-key (deepseek-api-key)))

(defn streaming-reasoning []
  (let [ch (llm/deepseek-completion
            flash-model
            {:messages [{:role :user
                         :content "Think briefly, then list three Clojure strengths."}]
             :reasoning-effort :high
             :stream true}
            :api-key (deepseek-api-key))]
    (async/go-loop []
      (when-let [chunk (async/<! ch)]
        (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning-content])]
          (println "\n[thinking]" reasoning))
        (when-let [content (streaming/extract-content chunk)]
          (print content)
          (flush))
        (recur)))))

(comment
  ;; export DEEPSEEK_API_KEY="..."
  (setup!)
  (basic-completion)
  (json-mode-completion)
  (streaming-reasoning))
