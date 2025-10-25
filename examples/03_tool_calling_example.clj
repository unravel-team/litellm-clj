(ns examples.03-tool-calling-example
  "Examples demonstrating tool calling with Anthropic and Gemini providers"
  (:require [litellm.router :as router]
            [cheshire.core :as json]))

;; ============================================================================
;; Setup
;; ============================================================================

(defn setup! []
  "Register configs for providers that support tool calling"
  ;; Register Anthropic config
  (when (System/getenv "ANTHROPIC_API_KEY")
    (router/register! :anthropic
      {:provider :anthropic
       :model "claude-3-5-sonnet-20241022"
       :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}}))
  
  ;; Register Gemini config
  (when (System/getenv "GEMINI_API_KEY")
    (router/register! :gemini
      {:provider :gemini
       :model "gemini-2.5-flash"
       :config {:api-key (System/getenv "GEMINI_API_KEY")}})))

;; ============================================================================
;; Tool Definitions
;; ============================================================================

(def weather-tool
  "Tool definition for getting weather information"
  {:type "function"
   :function {:name "getWeather"
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
   :function {:name "calculate"
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
    {:location location
     :temperature temp
     :unit unit
     :condition condition
     :humidity 65}))

(defn calculator
  "Perform a calculation"
  [operation a b]
  (let [result (case operation
                 "add" (+ a b)
                 "subtract" (- a b)
                 "multiply" (* a b)
                 "divide" (/ a b))]
    {:operation operation
     :a a
     :b b
     :result result}))

(defn execute-tool-call
  "Execute a tool call and return the result as a string"
  [tool-call]
  (let [function-name (get-in tool-call [:function :name])
        arguments (json/decode (get-in tool-call [:function :arguments]) true)]
    (json/encode
      (case function-name
        "getWeather" (get-weather (:location arguments) (or (:unit arguments) "fahrenheit"))
        "calculate" (calculator (:operation arguments) (:a arguments) (:b arguments))
        {:error "Unknown function"}))))

;; ============================================================================
;; Example 1: Simple Weather Query with Anthropic
;; ============================================================================

(defn simple-weather-example
  "Simple example of tool calling with Anthropic"
  []
  (println "\n=== Simple Weather Tool Calling Example ===\n")
  
  ;; Ensure setup is done
  (when-not (seq (router/list-configs))
    (setup!))
  
  (when (System/getenv "ANTHROPIC_API_KEY")
    ;; Step 1: Initial request with tool definition
    (let [response (router/completion :anthropic
                     {:messages [{:role :user 
                                 :content "What's the weather like in San Francisco?"}]
                      :tools [weather-tool]
                      :tool-choice :auto
                      :max-tokens 1024})]
      
      (println "Initial response:")
      (println "Content:" (get-in response [:choices 0 :message :content]))
      
      (let [tool-calls (get-in response [:choices 0 :message :tool-calls])]
        (println "Tool calls:" (count (or tool-calls [])))
        
        (when (seq tool-calls)
          ;; Step 2: Execute tool calls
          (let [tool-results (mapv (fn [tool-call]
                                     {:role :tool
                                      :tool-call-id (:id tool-call)
                                      :content (execute-tool-call tool-call)})
                                   tool-calls)
                
                ;; Step 3: Send tool results back to the model
                messages [{:role :user :content "What's the weather like in San Francisco?"}
                         (get-in response [:choices 0 :message])]
                messages-with-results (into messages tool-results)
                
                final-response (router/completion :anthropic
                                 {:messages messages-with-results
                                  :max-tokens 1024})]
            
            (println "\nTool execution results:")
            (doseq [result tool-results]
              (println "  -" (:content result)))
            
            (println "\nFinal response:")
            (println (get-in final-response [:choices 0 :message :content])))))))
  
  (when-not (System/getenv "ANTHROPIC_API_KEY")
    (println "Skipped: ANTHROPIC_API_KEY not set")))

;; ============================================================================
;; Example 2: Calculator (Using Anthropic)
;; ============================================================================

(defn calculator-example
  "Example of tool calling with calculator tool"
  []
  (println "\n=== Calculator Tool Calling Example ===\n")
  
  ;; Ensure setup is done
  (when-not (seq (router/list-configs))
    (setup!))
  
  (when (System/getenv "ANTHROPIC_API_KEY")
    ;; Step 1: Initial request
    (let [response (router/completion :anthropic
                                      {:messages [{:role :user 
                                                   :content "What is 123 multiplied by 456?"}]
                                       :tools [calculator-tool]
                                       :tool-choice :auto
                                       :max-tokens 1024})]
      
      (println "Initial response:")
      (println response)
      (println "Content:" (-> response
                              :choices
                              first
                              :message
                              :tool-calls))
      
      (let [tool-calls (-> response
                           :choices
                           first
                           :message
                           :tool-calls)]
        (println "Tool calls:" (count (or tool-calls [])))
        
        (when (seq tool-calls)
          ;; Execute and respond
          (let [tool-results (mapv (fn [tool-call]
                                     {:role :tool
                                      :tool-call-id (:id tool-call)
                                      :content (execute-tool-call tool-call)})
                                   tool-calls)
                
                messages [{:role :user :content "What is 123 multiplied by 456?"}
                          (-> response
                           :choices
                           first
                           :message)]
                messages-with-results (into messages tool-results)
                _ (println messages-with-results)
                final-response (router/completion :anthropic
                                                  {:messages messages-with-results
                                                   :max-tokens 1024})]
            
            (println "\nTool execution results:")
            (doseq [result tool-results]
              (println "  -" (:content result)))
            
            (println "\nFinal response:")
            (println (get-in final-response [:choices 0 :message :content])))))))
  
  (when-not (System/getenv "ANTHROPIC_API_KEY")
    (println "Skipped: ANTHROPIC_API_KEY not set")))

;; ============================================================================
;; Example 3: Multiple Tools
;; ============================================================================

(defn multi-tool-example
  "Example with multiple tools"
  []
  (println "\n=== Multi-Tool Example ===\n")
  
  ;; Ensure setup is done
  (when-not (seq (router/list-configs))
    (setup!))
  
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [tools [weather-tool calculator-tool]
          response (router/completion :anthropic
                     {:messages [{:role :user 
                                 :content "What's the weather in New York, and what's 25 times 4?"}]
                      :tools tools
                      :tool-choice :auto
                      :max-tokens 1024})]
      
      (println "Assistant's plan:")
      (println (get-in response [:choices 0 :message :content]))
      
      (let [tool-calls (get-in response [:choices 0 :message :tool-calls])]
        (when (seq tool-calls)
          (println "\nExecuting" (count tool-calls) "tool call(s):")
          (doseq [tc tool-calls]
            (println "  -" (get-in tc [:function :name])))
          
          (let [tool-results (mapv (fn [tool-call]
                                     {:role :tool
                                      :tool-call-id (:id tool-call)
                                      :content (execute-tool-call tool-call)})
                                   tool-calls)
                
                messages [{:role :user :content "What's the weather in New York, and what's 25 times 4?"}
                         (get-in response [:choices 0 :message])]
                messages-with-results (into messages tool-results)
                
                final-response (router/completion :anthropic
                                 {:messages messages-with-results
                                  :max-tokens 1024})]
            
            (println "\nFinal response:")
            (println (get-in final-response [:choices 0 :message :content])))))))
  
  (when-not (System/getenv "ANTHROPIC_API_KEY")
    (println "Skipped: ANTHROPIC_API_KEY not set")))

;; ============================================================================
;; Main Function
;; ============================================================================

(defn -main
  "Run all tool calling examples"
  [& args]
  (println "=================================================")
  (println "LiteLLM Tool Calling Examples")
  (println "=================================================")
  
  ;; Setup configs
  (setup!)
  
  ;; Run examples
  (simple-weather-example)
  (calculator-example)
  (multi-tool-example)
  
  (println "\n=================================================")
  (println "Examples completed!")
  (println "================================================="))

(comment
  ;; Set up environment variables:
  ;; export ANTHROPIC_API_KEY="your-key"
  ;; export GEMINI_API_KEY="your-key"
  
  ;; Then run setup
  (setup!)
  
  ;; Run individual examples
  (simple-weather-example)  ;; Uses Anthropic
  (calculator-example)       ;; Uses Gemini
  (multi-tool-example)       ;; Uses Anthropic
  
  ;; Or run all
  (-main))
