(ns examples.12-zai-glm-example
  "Z.AI GLM provider examples for litellm-clj."
  (:require [clojure.core.async :as async]
            [clojure.pprint :as pprint]
            [litellm.core :as llm]
            [litellm.router :as router]
            [litellm.streaming :as streaming]))

(defn zai-api-key []
  (System/getenv "ZAI_API_KEY"))

(defn setup! []
  (router/setup-zai!
   :model "glm-5.2"
   :api-key (zai-api-key)))

(defn basic-completion []
  (let [response (llm/zai-completion
                  "glm-5.2"
                  {:messages [{:role :user
                               :content "Explain one advantage of persistent data structures."}]
                   :max-tokens 200
                   :reasoning-effort :high
                   :thinking {:type :enabled :clear-thinking false}}
                  :api-key (zai-api-key))]
    (println "Answer:" (llm/extract-content response))
    (when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
      (println "Reasoning:" reasoning))
    (println "Usage:")
    (pprint/pprint (:usage response))))

(defn json-mode-completion []
  (llm/zai-completion
   "glm-5.2"
   {:messages [{:role :system
                :content "Return only valid JSON."}
               {:role :user
                :content "Return {\"provider\":\"zai\",\"model\":\"glm-5.2\"}."}]
    :response-format {:type :json-object}
    :do-sample false
    :max-tokens 200}
   :api-key (zai-api-key)))

(defn automatic-tool-choice-example []
  (llm/zai-completion
   "glm-5.2"
   {:messages [{:role :user
                :content "What tool would you call to get weather in Paris?"}]
    :tools [{:type "function"
             :function {:name "get_weather"
                        :description "Get current weather for a city"
                        :parameters {:type "object"
                                     :properties {:city {:type "string"}}
                                     :required ["city"]}}}]
    :tool-choice :auto
    :tool-stream true
    :max-tokens 300}
   :api-key (zai-api-key)))

(defn streaming-reasoning []
  (let [ch (llm/zai-completion
            "glm-5.2"
            {:messages [{:role :user
                         :content "Think briefly, then name two immutable data benefits."}]
             :reasoning-effort :high
             :stream true}
            :api-key (zai-api-key))]
    (async/go-loop []
      (when-let [chunk (async/<! ch)]
        (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning-content])]
          (println "\n[thinking]" reasoning))
        (when-let [content (streaming/extract-content chunk)]
          (print content)
          (flush))
        (recur)))))

(comment
  ;; export ZAI_API_KEY="..."
  (setup!)
  (basic-completion)
  (json-mode-completion)
  (automatic-tool-choice-example)
  (streaming-reasoning))
