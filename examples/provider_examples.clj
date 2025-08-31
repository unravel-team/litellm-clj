(ns examples.provider-examples
  (:require [litellm.core :as litellm]))

;; ============================================================================
;; OpenAI Example
;; ============================================================================

(defn openai-example
  "Example of using OpenAI provider"
  [api-key]
  (println "Testing OpenAI provider...")
  (let [response (litellm/completion
                   {:model "openai/gpt-3.5-turbo"
                    :api-key api-key
                    :messages [{:role :user
                                :content "What is the capital of France?"}]
                    :max-tokens 100})]
    (println "OpenAI Response:")
    (println (-> response :choices first :message :content))
    (println "Usage:" (:usage response))
    response))

;; ============================================================================
;; Anthropic Example
;; ============================================================================

(defn anthropic-example
  "Example of using Anthropic provider"
  [api-key]
  (println "Testing Anthropic provider...")
  (let [response (litellm/completion
                   {:model "anthropic/claude-3-haiku"
                    :api-key api-key
                    :messages [{:role :user
                                :content "What is the capital of Germany?"}]
                    :max-tokens 100})]
    (println "Anthropic Response:")
    (println (-> response :choices first :message :content))
    (println "Usage:" (:usage response))
    response))

;; ============================================================================
;; OpenRouter Example
;; ============================================================================

(defn openrouter-example
  "Example of using OpenRouter provider"
  [api-key]
  (println "Testing OpenRouter provider...")
  (let [response (litellm/completion
                   {:model "openai/gpt-3.5-turbo" ;; Using OpenAI model through OpenRouter
                    :api-key api-key
                    :messages [{:role :user
                                :content "What is the capital of Italy?"}]
                    :max-tokens 100})]
    (println "OpenRouter Response:")
    (println (-> response :choices first :message :content))
    (println "Usage:" (:usage response))
    response))

;; ============================================================================
;; System Prompt Example
;; ============================================================================

(defn system-prompt-example
  "Example of using system prompts with different providers"
  [openai-key anthropic-key]
  (println "Testing system prompts...")
  
  ;; OpenAI with system prompt
  (let [openai-response (litellm/completion
                          {:model "openai/gpt-3.5-turbo"
                           :api-key openai-key
                           :messages [{:role :system
                                       :content "You are a helpful geography teacher."}
                                      {:role :user
                                       :content "What are the three largest countries by area?"}]
                           :max-tokens 100})]
    (println "OpenAI Response with system prompt:")
    (println (-> openai-response :choices first :message :content)))
  
  ;; Anthropic with system prompt
  (let [anthropic-response (litellm/completion
                             {:model "anthropic/claude-3-haiku"
                              :api-key anthropic-key
                              :messages [{:role :system
                                          :content "You are a helpful geography teacher."}
                                         {:role :user
                                          :content "What are the three largest countries by area?"}]
                              :max-tokens 100})]
    (println "Anthropic Response with system prompt:")
    (println (-> anthropic-response :choices first :message :content))))

;; ============================================================================
;; Function Calling Example (OpenAI only)
;; ============================================================================

(defn function-calling-example
  "Example of using function calling with OpenAI"
  [api-key]
  (println "Testing function calling with OpenAI...")
  (let [tools [{:tool-type "function"
                :function {:function-name "get_weather"
                          :function-description "Get the current weather in a given location"
                          :function-parameters {:type "object"
                                               :properties {"location" {:type "string"
                                                                       :description "The city and state, e.g. San Francisco, CA"}
                                                           "unit" {:type "string"
                                                                  :enum ["celsius" "fahrenheit"]
                                                                  :description "The temperature unit to use"}}
                                               :required ["location"]}}}]
        response (litellm/completion
                   {:model "openai/gpt-4"
                    :api-key api-key
                    :messages [{:role :user
                                :content "What's the weather like in Paris?"}]
                    :tools tools
                    :tool-choice "auto"
                    :max-tokens 100})]
    (println "Function Calling Response:")
    (println (-> response :choices first :message))
    response))

;; ============================================================================
;; Run All Examples
;; ============================================================================

(defn run-all-examples
  "Run all examples with provided API keys"
  [& {:keys [openai-key anthropic-key openrouter-key]}]
  (when openai-key
    (openai-example openai-key)
    (println))
  
  (when anthropic-key
    (anthropic-example anthropic-key)
    (println))
  
  (when openrouter-key
    (openrouter-example openrouter-key)
    (println))
  
  (when (and openai-key anthropic-key)
    (system-prompt-example openai-key anthropic-key)
    (println))
  
  (when openai-key
    (function-calling-example openai-key)))

;; ============================================================================
;; Usage
;; ============================================================================

;; To run these examples, you need to provide your API keys:
;; 
;; (run-all-examples
;;   :openai-key "your-openai-key"
;;   :anthropic-key "your-anthropic-key"
;;   :openrouter-key "your-openrouter-key")
;;
;; Or run individual examples:
;;
(comment
  (openai-example "your-openai-key")
  (anthropic-example "your-anthropic-key")
  (openrouter-example "your-openrouter-key"))
