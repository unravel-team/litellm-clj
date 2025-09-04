(ns examples.gemini-example
  "Example usage of the Google Gemini provider"
  (:require [litellm.core :as litellm]
            [litellm.providers.gemini :as gemini]))

;; Example 1: Basic usage with Gemini
(defn basic-gemini-usage []
  (let [provider (litellm/create-provider {:provider "gemini"
                                          :api-key (System/getenv "GEMINI_API_KEY")})
        request {:model "gemini-1.5-flash"
                :messages [{:role :user :content "What is the capital of France?"}]
                :max-tokens 100}]
    (litellm/chat provider request)))

;; Example 2: Using system instructions
(defn gemini-with-system []
  (let [provider (litellm/create-provider {:provider "gemini"
                                          :api-key (System/getenv "GEMINI_API_KEY")})
        request {:model "gemini-1.5-pro"
                :messages [{:role :system :content "You are a helpful assistant that speaks like Shakespeare."}
                          {:role :user :content "Tell me about the weather today"}]
                :temperature 0.7
                :max-tokens 150}]
    (litellm/chat provider request)))

;; Example 3: Function calling with Gemini
(defn gemini-function-calling []
  (let [provider (litellm/create-provider {:provider "gemini"
                                          :api-key (System/getenv "GEMINI_API_KEY")})
        tools [{:tool-type "function"
                :function {:function-name "get_weather"
                          :function-description "Get the current weather for a location"
                          :function-parameters {:type "object"
                                               :properties {:location {:type "string"
                                                                      :description "The city and state"}}
                                               :required ["location"]}}}]
        request {:model "gemini-1.5-flash"
                :messages [{:role :user :content "What's the weather like in Boston?"}]
                :tools tools
                :tool-choice :auto}]
    (litellm/chat provider request)))

;; Example 4: Streaming responses
(defn gemini-streaming []
  (let [provider (litellm/create-provider {:provider "gemini"
                                          :api-key (System/getenv "GEMINI_API_KEY")})
        request {:model "gemini-1.5-flash"
                :messages [{:role :user :content "Write a short story about a robot learning to paint"}]
                :stream true
                :max-tokens 200}]
    (litellm/chat provider request)))

;; Example 5: Health check
(defn check-gemini-health []
  (let [provider (litellm/create-provider {:provider "gemini"
                                          :api-key (System/getenv "GEMINI_API_KEY")})]
    (litellm/health-check provider)))

;; Example 6: List available models
(defn list-gemini-models []
  (let [provider (litellm/create-provider {:provider "gemini"
                                          :api-key (System/getenv "GEMINI_API_KEY")})]
    (gemini/list-models provider)))

(comment
  ;; Run examples (make sure to set GEMINI_API_KEY environment variable)
  (basic-gemini-usage)
  (gemini-with-system)
  (gemini-function-calling)
  (gemini-streaming)
  (check-gemini-health)
  (list-gemini-models))
