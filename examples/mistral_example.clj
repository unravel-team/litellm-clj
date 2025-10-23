(ns mistral-example
  "Example usage of Mistral AI provider with litellm-clj"
  (:require [litellm.core :as litellm]))

;; ============================================================================
;; Basic Setup
;; ============================================================================

;; Set your Mistral API key (or set MISTRAL_API_KEY environment variable)
(def api-key (or (System/getenv "MISTRAL_API_KEY") "your-api-key-here"))

;; ============================================================================
;; Example 1: Basic Chat Completion
;; ============================================================================

(defn basic-chat-example []
  (println "\n=== Basic Chat Example ===")
  (let [response (litellm/completion :mistral "mistral-small-latest"
                                     {:messages [{:role :user 
                                                :content "What is the capital of France?"}]
                                      :max-tokens 100
                                      :api-key api-key})]
    (println "Response:" (get-in response [:choices 0 :message :content]))
    response))

;; ============================================================================
;; Example 2: Chat with Temperature Control
;; ============================================================================

(defn temperature-example []
  (println "\n=== Temperature Control Example ===")
  (let [response (litellm/completion :mistral "mistral-small-latest"
                                     {:messages [{:role :user 
                                                :content "Write a creative short story about a robot."}]
                                      :max-tokens 200
                                      :temperature 0.9
                                      :api-key api-key})]
    (println "Creative response:" (get-in response [:choices 0 :message :content]))
    response))

;; ============================================================================
;; Example 3: Multi-turn Conversation
;; ============================================================================

(defn conversation-example []
  (println "\n=== Multi-turn Conversation Example ===")
  (let [response (litellm/completion :mistral "mistral-small-latest"
                                     {:messages [{:role :system 
                                                :content "You are a helpful coding assistant."}
                                               {:role :user 
                                                :content "What is recursion?"}
                                               {:role :assistant 
                                                :content "Recursion is when a function calls itself."}
                                               {:role :user 
                                                :content "Can you give me an example in Clojure?"}]
                                      :max-tokens 300
                                      :api-key api-key})]
    (println "Assistant:" (get-in response [:choices 0 :message :content]))
    response))

;; ============================================================================
;; Example 4: Using Mistral Large for Complex Tasks
;; ============================================================================

(defn large-model-example []
  (println "\n=== Mistral Large Example ===")
  (let [response (litellm/completion :mistral "mistral-large-latest"
                                     {:messages [{:role :user 
                                                :content "Explain quantum computing in simple terms."}]
                                      :max-tokens 500
                                      :temperature 0.7
                                      :api-key api-key})]
    (println "Explanation:" (get-in response [:choices 0 :message :content]))
    response))

;; ============================================================================
;; Example 5: Function Calling
;; ============================================================================

(defn function-calling-example []
  (println "\n=== Function Calling Example ===")
  (let [tools [{:tool-type "function"
               :function {:name "get_weather"
                         :description "Get the current weather for a location"
                         :parameters {:type "object"
                                    :properties {:location {:type "string"
                                                          :description "City and state, e.g. San Francisco, CA"}
                                               :unit {:type "string"
                                                     :enum ["celsius" "fahrenheit"]}}
                                    :required ["location"]}}}]
        response (litellm/completion :mistral "mistral-large-latest"
                                     {:messages [{:role :user 
                                                :content "What's the weather like in Paris?"}]
                                      :tools tools
                                      :tool-choice :auto
                                      :api-key api-key})]
    (println "Response:" response)
    (when-let [tool-calls (get-in response [:choices 0 :message :tool-calls])]
      (println "Tool calls:" tool-calls))
    response))

;; ============================================================================
;; Example 6: Reasoning with Magistral Models
;; ============================================================================

(defn reasoning-example []
  (println "\n=== Reasoning Example (Magistral Model) ===")
  (let [response (litellm/completion :mistral "magistral-medium-2506"
                                     {:messages [{:role :user 
                                                :content "What is 15 multiplied by 7? Show your reasoning."}]
                                      :reasoning-effort "medium"
                                      :max-tokens 500
                                      :api-key api-key})]
    (println "Reasoning response:" (get-in response [:choices 0 :message :content]))
    response))

;; ============================================================================
;; Example 7: Code Generation with Codestral
;; ============================================================================

(defn code-generation-example []
  (println "\n=== Code Generation Example (Codestral) ===")
  (let [response (litellm/completion :mistral "codestral-latest"
                                     {:messages [{:role :user 
                                                :content "Write a Clojure function to calculate fibonacci numbers."}]
                                      :max-tokens 300
                                      :api-key api-key})]
    (println "Generated code:" (get-in response [:choices 0 :message :content]))
    response))

;; ============================================================================
;; Example 8: Simple Chat Helper
;; ============================================================================

(defn simple-chat-example []
  (println "\n=== Simple Chat Helper ===")
  (let [response (litellm/chat :mistral "mistral-small-latest" "What is 2+2?"
                                :api-key api-key)]
    (println "Response:" (litellm/extract-content response))
    response))

;; ============================================================================
;; Run All Examples
;; ============================================================================

(defn run-all-examples []
  (println "Running Mistral AI Examples\n")
  (println "Make sure to set your MISTRAL_API_KEY environment variable!")
  
  ;; Run each example
  (basic-chat-example)
  (temperature-example)
  (conversation-example)
  (large-model-example)
  (function-calling-example)
  (reasoning-example)
  (code-generation-example)
  (simple-chat-example)
  
  (println "\n=== All examples completed! ==="))

;; Uncomment to run:
;; (run-all-examples)

;; Or run individual examples:
;; (basic-chat-example)
;; (function-calling-example)
;; (reasoning-example)
