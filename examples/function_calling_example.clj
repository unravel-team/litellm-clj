(ns examples.function-calling-example
  "Examples demonstrating function calling with Anthropic and Gemini providers"
  (:require [litellm.core :as litellm]
            [cheshire.core :as json]))

;; ============================================================================
;; Tool Definitions
;; ============================================================================

(def weather-tool
  "Tool definition for getting weather information"
  {:type "function"
   :function {:name "get_weather"
             :description "Get the current weather for a location"
             :parameters {:type "object"
                         :properties {:location {:type "string"
                                                :description "The city and state, e.g. San Francisco, CA"}
                                     :unit {:type "string"
                                           :enum ["celsius" "fahrenheit"]
                                           :description "The temperature unit to use"}}
                         :required ["location"]}}})

(def calculator-tool
  "Tool definition for performing calculations"
  {:type "function"
   :function {:name "calculator"
             :description "Perform a mathematical calculation"
             :parameters {:type "object"
                         :properties {:operation {:type "string"
                                                 :enum ["add" "subtract" "multiply" "divide"]
                                                 :description "The mathematical operation to perform"}
                                     :a {:type "number"
                                        :description "First number"}
                                     :b {:type "number"
                                        :description "Second number"}}
                         :required ["operation" "a" "b"]}}})

;; ============================================================================
;; Tool Implementation Functions
;; ============================================================================

(defn get-weather
  "Simulate getting weather data for a location"
  [location unit]
  (let [temp (if (= unit "celsius") 22 72)
        condition "sunny"]
    (json/encode {:location location
                  :temperature temp
                  :unit unit
                  :condition condition
                  :humidity 65})))

(defn calculator
  "Perform a calculation"
  [operation a b]
  (let [result (case operation
                 "add" (+ a b)
                 "subtract" (- a b)
                 "multiply" (* a b)
                 "divide" (/ a b))]
    (json/encode {:operation operation
                  :a a
                  :b b
                  :result result})))

(defn execute-tool-call
  "Execute a tool call and return the result"
  [tool-call]
  (let [function-name (get-in tool-call [:function :name])
        arguments (json/decode (get-in tool-call [:function :arguments]) true)]
    (case function-name
      "get_weather" (get-weather (:location arguments) (or (:unit arguments) "fahrenheit"))
      "calculator" (calculator (:operation arguments) (:a arguments) (:b arguments))
      (json/encode {:error "Unknown function"}))))

;; ============================================================================
;; Example 1: Simple Weather Query with Anthropic
;; ============================================================================

(defn anthropic-weather-example
  "Example of function calling with Anthropic"
  []
  (println "\n=== Anthropic Weather Function Calling Example ===\n")
  
  ;; Step 1: Initial request with tool definition
  (let [initial-response (litellm/chat-completion
                          {:provider :anthropic
                           :model "claude-3-sonnet"
                           :messages [{:role :user 
                                      :content "What's the weather like in San Francisco?"}]
                           :tools [weather-tool]
                           :tool-choice :auto
                           :max-tokens 1024})
        
        ;; Check if the model wants to call a tool
        tool-calls (get-in initial-response [:choices 0 :message :tool-calls])]
    
    (println "Initial response:")
    (println "Content:" (get-in initial-response [:choices 0 :message :content]))
    (println "Tool calls:" tool-calls)
    
    (when (seq tool-calls)
      ;; Step 2: Execute tool calls
      (let [tool-results (map (fn [tool-call]
                                {:role :tool
                                 :tool-call-id (:id tool-call)
                                 :content (execute-tool-call tool-call)})
                              tool-calls)
            
            ;; Step 3: Send tool results back to the model
            messages [{:role :user :content "What's the weather like in San Francisco?"}
                     (get-in initial-response [:choices 0 :message])
                     (first tool-results)]
            
            final-response (litellm/chat-completion
                           {:provider :anthropic
                            :model "claude-3-sonnet"
                            :messages messages
                            :max-tokens 1024})]
        
        (println "\nTool execution results:")
        (doseq [result tool-results]
          (println "  Tool result:" (:content result)))
        
        (println "\nFinal response:")
        (println (get-in final-response [:choices 0 :message :content]))))))

;; ============================================================================
;; Example 2: Calculator with Gemini
;; ============================================================================

(defn gemini-calculator-example
  "Example of function calling with Gemini"
  []
  (println "\n=== Gemini Calculator Function Calling Example ===\n")
  
  ;; Step 1: Initial request with tool definition
  (let [initial-response (litellm/chat-completion
                          {:provider :gemini
                           :model "gemini-1.5-flash"
                           :messages [{:role :user 
                                      :content "What is 123 multiplied by 456?"}]
                           :tools [calculator-tool]
                           :tool-choice :auto
                           :max-tokens 1024})
        
        ;; Check if the model wants to call a tool
        tool-calls (get-in initial-response [:choices 0 :message :tool-calls])]
    
    (println "Initial response:")
    (println "Content:" (get-in initial-response [:choices 0 :message :content]))
    (println "Tool calls:" tool-calls)
    
    (when (seq tool-calls)
      ;; Step 2: Execute tool calls
      (let [tool-results (map (fn [tool-call]
                                {:role :tool
                                 :tool-call-id (:id tool-call)
                                 :content (execute-tool-call tool-call)})
                              tool-calls)
            
            ;; Step 3: Send tool results back to the model
            messages [{:role :user :content "What is 123 multiplied by 456?"}
                     (get-in initial-response [:choices 0 :message])
                     (first tool-results)]
            
            final-response (litellm/chat-completion
                           {:provider :gemini
                            :model "gemini-1.5-flash"
                            :messages messages
                            :max-tokens 1024})]
        
        (println "\nTool execution results:")
        (doseq [result tool-results]
          (println "  Tool result:" (:content result)))
        
        (println "\nFinal response:")
        (println (get-in final-response [:choices 0 :message :content]))))))

;; ============================================================================
;; Example 3: Multi-turn Conversation with Multiple Tools
;; ============================================================================

(defn multi-tool-example
  "Example with multiple tools and multi-turn conversation"
  [provider model]
  (println (str "\n=== Multi-Tool Example with " (name provider) " ===\n"))
  
  (let [tools [weather-tool calculator-tool]
        initial-messages [{:role :user 
                          :content "What's the weather in New York, and what's 25 times 4?"}]]
    
    (loop [messages initial-messages
           turn 1]
      (when (<= turn 3)  ; Limit to 3 turns to prevent infinite loops
        (println (str "\n--- Turn " turn " ---"))
        
        (let [response (litellm/chat-completion
                       {:provider provider
                        :model model
                        :messages messages
                        :tools tools
                        :tool-choice :auto
                        :max-tokens 1024})
              
              assistant-message (get-in response [:choices 0 :message])
              tool-calls (:tool-calls assistant-message)]
          
          (println "Assistant:" (:content assistant-message))
          
          (if (seq tool-calls)
            (do
              (println "Tool calls requested:" (count tool-calls))
              (let [tool-results (map (fn [tool-call]
                                        (println "  Executing:" (get-in tool-call [:function :name]))
                                        {:role :tool
                                         :tool-call-id (:id tool-call)
                                         :content (execute-tool-call tool-call)})
                                      tool-calls)]
                (recur (concat messages [assistant-message] tool-results)
                       (inc turn))))
            (println "\nConversation complete!")))))))

;; ============================================================================
;; Example 4: Forced Tool Usage
;; ============================================================================

(defn forced-tool-example
  "Example of forcing the model to use a specific tool"
  []
  (println "\n=== Forced Tool Usage Example ===\n")
  
  (let [response (litellm/chat-completion
                 {:provider :anthropic
                  :model "claude-3-sonnet"
                  :messages [{:role :user 
                             :content "Tell me about the weather"}]
                  :tools [weather-tool]
                  :tool-choice :any  ; Force the model to use a tool
                  :max-tokens 1024})]
    
    (println "With tool-choice :any, the model must use a tool:")
    (println "Tool calls:" (get-in response [:choices 0 :message :tool-calls]))))

;; ============================================================================
;; Main Function
;; ============================================================================

(defn -main
  "Run all function calling examples"
  [& args]
  (println "=================================================")
  (println "LiteLLM Function Calling Examples")
  (println "=================================================")
  
  ;; Run examples
  (anthropic-weather-example)
  (gemini-calculator-example)
  (multi-tool-example :anthropic "claude-3-sonnet")
  (multi-tool-example :gemini "gemini-1.5-flash")
  (forced-tool-example)
  
  (println "\n=================================================")
  (println "Examples completed!")
  (println "================================================="))

(comment
  ;; Run individual examples
  (anthropic-weather-example)
  (gemini-calculator-example)
  (multi-tool-example :anthropic "claude-3-sonnet")
  (multi-tool-example :gemini "gemini-1.5-flash")
  (forced-tool-example)
  
  ;; Run all examples
  (-main))
