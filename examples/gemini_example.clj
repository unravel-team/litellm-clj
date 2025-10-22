(ns examples.gemini-example
  "Example usage of the Google Gemini provider"
  (:require [litellm.core :as litellm]
            [litellm.providers.gemini :as gemini]))

;; Example 1: Basic usage with Gemini
(defn basic-gemini-usage []
  (litellm/completion :gemini "gemini-1.5-flash"
                      {:messages [{:role :user :content "What is the capital of France?"}]
                       :max-tokens 100
                       :api-key (System/getenv "GEMINI_API_KEY")}))

;; Example 2: Using system instructions
(defn gemini-with-system []
  (litellm/completion :gemini "gemini-1.5-pro"
                      {:messages [{:role :system :content "You are a helpful assistant that speaks like Shakespeare."}
                                  {:role :user :content "Tell me about the weather today"}]
                       :temperature 0.7
                       :max-tokens 150
                       :api-key (System/getenv "GEMINI_API_KEY")}))

;; Example 3: Function calling with Gemini
(defn gemini-function-calling []
  (let [tools [{:tool-type "function"
                :function {:function-name "get_weather"
                          :function-description "Get the current weather for a location"
                          :function-parameters {:type "object"
                                               :properties {:location {:type "string"
                                                                      :description "The city and state"}}
                                               :required ["location"]}}}]]
    (litellm/completion :gemini "gemini-1.5-flash"
                        {:messages [{:role :user :content "What's the weather like in Boston?"}]
                         :tools tools
                         :tool-choice :auto
                         :api-key (System/getenv "GEMINI_API_KEY")})))

;; Example 4: Streaming responses
(defn gemini-streaming []
  (litellm/completion :gemini "gemini-1.5-flash"
                      {:messages [{:role :user :content "Write a short story about a robot learning to paint"}]
                       :stream true
                       :max-tokens 200
                       :api-key (System/getenv "GEMINI_API_KEY")}))

;; Example 5: Simple chat helper
(defn simple-chat []
  (litellm/chat :gemini "gemini-1.5-flash" "What is 2+2?"
                :api-key (System/getenv "GEMINI_API_KEY")))

(comment
  ;; Run examples (make sure to set GEMINI_API_KEY environment variable)
  (basic-gemini-usage)
  (gemini-with-system)
  (gemini-function-calling)
  (gemini-streaming)
  (simple-chat))
