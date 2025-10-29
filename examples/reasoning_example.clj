(ns examples.reasoning-example
  "Example demonstrating reasoning/thinking content support with Anthropic models"
  (:require [litellm.core :as litellm]
            [clojure.pprint :as pprint]
            [cheshire.core :as json]
            [clojure.core.async :as async]))

;; ============================================================================
;; Basic Reasoning with reasoning-effort
;; ============================================================================

(defn basic-reasoning-example
  "Demonstrate basic reasoning with reasoning-effort parameter"
  []
  (println "\n=== Basic Reasoning Example ===\n")
  
  (let [response (litellm/completion
                  :anthropic
                  "claude-3-7-sonnet-20250219"
                  {:messages [{:role :user 
                              :content "What is the capital of France?"}]
                   :reasoning-effort :low}
                  {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    
    (println "Response:")
    (println "Content:" (get-in response [:choices 0 :message :content]))
    (when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
      (println "\nReasoning Content:")
      (println reasoning))
    (when-let [thinking-blocks (get-in response [:choices 0 :message :thinking-blocks])]
      (println "\nThinking Blocks:")
      (pprint/pprint thinking-blocks))))

;; ============================================================================
;; Advanced Reasoning with thinking config
;; ============================================================================

(defn advanced-reasoning-example
  "Demonstrate reasoning with explicit thinking configuration"
  []
  (println "\n=== Advanced Reasoning Example ===\n")
  
  (let [response (litellm/completion
                  :anthropic
                  "claude-3-7-sonnet-20250219"
                  {:messages [{:role :user 
                              :content "Solve this problem step by step: If a train travels 120 km in 2 hours, what is its average speed?"}]
                   :thinking {:type :enabled
                             :budget-tokens 2048}}
                  {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    
    (println "Response:")
    (println "Content:" (get-in response [:choices 0 :message :content]))
    (when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
      (println "\nReasoning Content:")
      (println reasoning))
    (println "\nUsage:")
    (pprint/pprint (:usage response))))

;; ============================================================================
;; Reasoning with Tool Calling
;; ============================================================================

(defn get-weather
  "Mock function to get weather"
  [location]
  (str "{\"location\": \"" location "\", \"temperature\": \"72\", \"unit\": \"fahrenheit\"}"))

(defn reasoning-with-tools-example
  "Demonstrate reasoning with tool calling"
  []
  (println "\n=== Reasoning with Tool Calling Example ===\n")
  
  ;; Step 1: Initial request with tools
  (let [tools [{:type "function"
                :function {:name "get_weather"
                          :description "Get the current weather"
                          :parameters {:type "object"
                                      :properties {:location {:type "string"
                                                             :description "City name"}}
                                      :required ["location"]}}}]
        
        response1 (litellm/completion
                   :anthropic
                   "claude-3-7-sonnet-20250219"
                   {:messages [{:role :user 
                               :content "What's the weather in Paris?"}]
                    :tools tools
                    :reasoning-effort :medium}
                   {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    
    (println "First Response:")
    (when-let [reasoning (get-in response1 [:choices 0 :message :reasoning-content])]
      (println "Reasoning:" reasoning))
    
    (let [tool-calls (get-in response1 [:choices 0 :message :tool-calls])]
      (println "\nTool Calls:" (count tool-calls))
      
      ;; Step 2: Call the tool and send results back
      (when (seq tool-calls)
        (let [tool-call (first tool-calls)
              tool-name (get-in tool-call [:function :name])
              tool-args (json/read-str (get-in tool-call [:function :arguments]) :key-fn keyword)
              tool-result (get-weather (:location tool-args))
              
              response2 (litellm/completion
                         :anthropic
                         "claude-3-7-sonnet-20250219"
                         {:messages [{:role :user 
                                     :content "What's the weather in Paris?"}
                                    {:role :assistant
                                     :content (get-in response1 [:choices 0 :message :content])
                                     :tool-calls tool-calls}
                                    {:role :tool
                                     :tool-call-id (:id tool-call)
                                     :content tool-result}]
                          :reasoning-effort :medium}
                         {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
          
          (println "\nFinal Response:")
          (println "Content:" (get-in response2 [:choices 0 :message :content]))
          (when-let [reasoning (get-in response2 [:choices 0 :message :reasoning-content])]
            (println "\nFinal Reasoning:" reasoning)))))))

;; ============================================================================
;; Streaming with Reasoning
;; ============================================================================

(defn streaming-reasoning-example
  "Demonstrate streaming responses with reasoning content"
  []
  (println "\n=== Streaming Reasoning Example ===\n")
  
  (let [stream-ch (litellm/completion
                   :anthropic
                   "claude-3-7-sonnet-20250219"
                   {:messages [{:role :user 
                               :content "Explain quantum entanglement in simple terms"}]
                    :reasoning-effort :high
                    :stream true}
                   {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
    
    (println "Streaming response:")
    (loop []
      (when-let [chunk (async/<!! stream-ch)]
        (when-let [content (get-in chunk [:choices 0 :delta :content])]
          (print content)
          (flush))
        (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning-content])]
          (println "\n[Reasoning chunk]:" reasoning))
        (recur)))
    (println "\n\nStream complete!")))

;; ============================================================================
;; Comparing Different Reasoning Efforts
;; ============================================================================

(defn compare-reasoning-efforts
  "Compare responses with different reasoning effort levels"
  []
  (println "\n=== Comparing Reasoning Efforts ===\n")
  
  (let [question "What are the implications of artificial intelligence on society?"
        efforts [:low :medium :high]]
    
    (doseq [effort efforts]
      (println (str "\n--- Reasoning Effort: " (name effort) " ---"))
      (let [response (litellm/completion
                      :anthropic
                      "claude-3-7-sonnet-20250219"
                      {:messages [{:role :user :content question}]
                       :reasoning-effort effort
                       :max-tokens 500}
                      {:api-key (System/getenv "ANTHROPIC_API_KEY")})]
        
        (println "Response length:" (count (get-in response [:choices 0 :message :content])))
        (println "Tokens used:" (get-in response [:usage :total-tokens]))
        (when-let [reasoning (get-in response [:choices 0 :message :reasoning-content])]
          (println "Reasoning length:" (count reasoning)))))))

;; ============================================================================
;; Main
;; ============================================================================

(defn -main
  []
  (try
    ;; Check for API key
    (when-not (System/getenv "ANTHROPIC_API_KEY")
      (println "ERROR: ANTHROPIC_API_KEY environment variable not set")
      (System/exit 1))
    
    ;; Run examples
    (basic-reasoning-example)
    (advanced-reasoning-example)
    (reasoning-with-tools-example)
    (streaming-reasoning-example)
    (compare-reasoning-efforts)
    
    (println "\n=== All examples completed successfully ===")
    
    (catch Exception e
      (println "\nError:" (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))

(comment
  ;; Run individual examples
  (basic-reasoning-example)
  (advanced-reasoning-example)
  (reasoning-with-tools-example)
  (streaming-reasoning-example)
  (compare-reasoning-efforts)
  
  ;; Run all
  (-main))
