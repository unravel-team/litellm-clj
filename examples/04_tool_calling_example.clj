(ns examples.04-tool-calling-example
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
       :model "claude-haiku-4-5-20251001"
       :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}}))
  
  ;; Register Gemini config
  (when (System/getenv "GEMINI_API_KEY")
    (router/register! :gemini
      {:provider :gemini
       :model "gemini-2.5-flash"
       :config {:api-key (System/getenv "GEMINI_API_KEY")}})))

;; ============================================================================
;; Tool Definitions (Standard OpenAI Format)
;; ============================================================================

(def weather-tool
  "Tool definition for getting weather information (OpenAI standard format)"
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
  "Tool definition for performing calculations (OpenAI standard format)"
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
  "Execute a tool call and return the result as a string.
   
   Tool call format (standard):
   {:id \"call_123\"
    :type \"function\"
    :function {:name \"functionName\"
              :arguments \"{...JSON string...\"}}"
  [tool-call]
  (let [function-name (get-in tool-call [:function :name])
        ;; Arguments come as a JSON string, need to decode
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
  "Simple example of tool calling with Anthropic.
   
   Flow:
   1. Send user message with tool definitions
   2. Model responds with tool calls
   3. Execute tool calls locally
   4. Send tool results back (including assistant message + tool result messages)
   5. Model responds with final answer"
  []
  (println "\n=== Simple Weather Tool Calling Example (Anthropic) ===\n")
  
  ;; Ensure setup is done
  (when-not (seq (router/list-configs))
    (setup!))
  
  (when (System/getenv "ANTHROPIC_API_KEY")
    ;; Step 1: Initial request with tool definition
    (let [user-message {:role :user 
                       :content "What's the weather like in San Francisco?"}
          response (router/completion :anthropic
                     {:messages [user-message]
                      :tools [weather-tool]
                      :tool-choice :auto
                      :max-tokens 1024})]
      
      (println "Step 1: Initial request sent")
      (println "Model thinking:" (get-in response [:choices 0 :message :content]))
      
      (let [assistant-message (get-in response [:choices 0 :message])
            tool-calls (:tool-calls assistant-message)]
        
        (if (seq tool-calls)
          (do
            (println "\nStep 2: Model requested tool calls:" (count tool-calls))
            (doseq [tc tool-calls]
              (println "  - Function:" (get-in tc [:function :name]))
              (println "    Arguments:" (get-in tc [:function :arguments])))
            
            ;; Step 3: Execute tool calls
            (println "\nStep 3: Executing tool calls...")
            (let [tool-results (mapv (fn [tool-call]
                                       ;; Tool result format (standard):
                                       ;; {:role :tool
                                       ;;  :tool-call-id "call_123"
                                       ;;  :content "result string"}
                                       {:role :tool
                                        :tool-call-id (:id tool-call)
                                        :content (execute-tool-call tool-call)})
                                     tool-calls)]
              
              (doseq [result tool-results]
                (println "  - Tool result:" (:content result)))
              
              ;; Step 4: Send tool results back
              ;; Important: Include user message, assistant message (with tool calls), then tool results
              (println "\nStep 4: Sending tool results back to model...")
              ;; Clean up assistant message - remove nil content
              (let [clean-assistant-message (if (:content assistant-message)
                                              assistant-message
                                              (dissoc assistant-message :content))
                    messages-with-results (into [user-message clean-assistant-message]
                                               tool-results)
                    
                    final-response (router/completion :anthropic
                                     {:messages messages-with-results
                                      :max-tokens 1024})]
                
                (println "\nStep 5: Final response from model:")
                (println (get-in final-response [:choices 0 :message :content])))))
          
          (do (println "\nNo tool calls requested. Direct response:")
              (println (get-in response [:choices 0 :message :content])))))))
  
  (when-not (System/getenv "ANTHROPIC_API_KEY")
    (println "Skipped: ANTHROPIC_API_KEY not set")))

;; ============================================================================
;; Example 2: Calculator with Gemini
;; ============================================================================

(defn calculator-example
  "Example of tool calling with calculator tool using Gemini"
  []
  (println "\n=== Calculator Tool Calling Example (Gemini) ===\n")
  
  ;; Ensure setup is done
  (when-not (seq (router/list-configs))
    (setup!))
  
  (when (System/getenv "GEMINI_API_KEY")
    ;; Step 1: Initial request
    (let [user-message {:role :user 
                       :content "What is 123 multiplied by 456?"}
          response (router/completion :gemini
                     {:messages [user-message]
                      :tools [calculator-tool]
                      :tool-choice :auto
                      :max-tokens 1024})]
      
      (println "Step 1: Initial request sent")
      (println "Model thinking:" (get-in response [:choices 0 :message :content]))
      
      (let [assistant-message (get-in response [:choices 0 :message])
            tool-calls (:tool-calls assistant-message)]
        
        (if (seq tool-calls)
          (do
            (println "\nStep 2: Model requested tool calls:" (count tool-calls))
            (doseq [tc tool-calls]
              (println "  - Function:" (get-in tc [:function :name]))
              (println "    Arguments:" (get-in tc [:function :arguments])))
            
            ;; Execute and respond
            (println "\nStep 3: Executing tool calls...")
            (let [tool-results (mapv (fn [tool-call]
                                       {:role :tool
                                        :tool-call-id (:id tool-call)
                                        :content (execute-tool-call tool-call)})
                                     tool-calls)]
              
              (doseq [result tool-results]
                (println "  - Tool result:" (:content result)))
              
              (println "\nStep 4: Sending tool results back to model...")
              ;; Clean up assistant message - remove nil content
              (let [clean-assistant-message (if (:content assistant-message)
                                              assistant-message
                                              (dissoc assistant-message :content))
                    messages-with-results (into [user-message clean-assistant-message]
                                               tool-results)
                    
                    final-response (router/completion :gemini
                                     {:messages messages-with-results
                                      :max-tokens 1024})]
                
                (println "\nStep 5: Final response from model:")
                (println (get-in final-response [:choices 0 :message :content])))))
          
          (do (println "\nNo tool calls requested. Direct response:")
              (println (get-in response [:choices 0 :message :content])))))))
  
  (when-not (System/getenv "GEMINI_API_KEY")
    (println "Skipped: GEMINI_API_KEY not set")))

;; ============================================================================
;; Example 3: Multiple Tools with Anthropic
;; ============================================================================

(defn multi-tool-example
  "Example with multiple tools - model can choose which to use"
  []
  (println "\n=== Multi-Tool Example (Anthropic) ===\n")
  
  ;; Ensure setup is done
  (when-not (seq (router/list-configs))
    (setup!))
  
  (when (System/getenv "ANTHROPIC_API_KEY")
    (let [user-message {:role :user 
                       :content "What's the weather in New York, and what's 25 times 4?"}
          tools [weather-tool calculator-tool]
          response (router/completion :anthropic
                     {:messages [user-message]
                      :tools tools
                      :tool-choice :auto
                      :max-tokens 1024})]
      
      (println "Step 1: Initial request sent")
      (println "Model thinking:" (get-in response [:choices 0 :message :content]))
      
      (let [assistant-message (get-in response [:choices 0 :message])
            tool-calls (:tool-calls assistant-message)]
        
        (when (seq tool-calls)
          (println "\nStep 2: Model requested" (count tool-calls) "tool call(s):")
          (doseq [tc tool-calls]
            (println "  -" (get-in tc [:function :name]) 
                    "with args:" (get-in tc [:function :arguments])))
          
          ;; Execute all tool calls
          (println "\nStep 3: Executing tool calls...")
          (let [tool-results (mapv (fn [tool-call]
                                     {:role :tool
                                      :tool-call-id (:id tool-call)
                                      :content (execute-tool-call tool-call)})
                                   tool-calls)]
            
            (doseq [result tool-results]
              (println "  - Result:" (:content result)))
            
            ;; Send back all results
            (println "\nStep 4: Sending all tool results back...")
            ;; Clean up assistant message - remove nil content
            (let [clean-assistant-message (if (:content assistant-message)
                                            assistant-message
                                            (dissoc assistant-message :content))
                  messages-with-results (into [user-message clean-assistant-message]
                                             tool-results)
                  
                  final-response (router/completion :anthropic
                                   {:messages messages-with-results
                                    :max-tokens 1024})]
              
              (println "\nStep 5: Final response:")
              (println (get-in final-response [:choices 0 :message :content])))))))
  
  (when-not (System/getenv "ANTHROPIC_API_KEY")
    (println "Skipped: ANTHROPIC_API_KEY not set"))))

;; ============================================================================
;; Example 4: Understanding Tool Call Format
;; ============================================================================

(defn format-demo-example
  "Demonstrates the exact format of tool calls and results"
  []
  (println "\n=== Tool Calling Format Demo ===\n")
  
  (println "Tool Definition Format (OpenAI standard):")
  (println "```clojure")
  (println "{:type \"function\"")
  (println " :function {:name \"functionName\"")
  (println "           :description \"Function description\"")
  (println "           :parameters {:type \"object\"")
  (println "                       :properties {...}") 
  (println "                       :required [...]}}}")
  (println "```")
  
  (println "\nTool Call Format (in response):")
  (println "```clojure")
  (println "{:id \"call_abc123\"")
  (println " :type \"function\"")
  (println " :function {:name \"functionName\"")
  (println "           :arguments \"{\\\"key\\\":\\\"value\\\"}\"}}  ; JSON string!")
  (println "```")
  
  (println "\nTool Result Format (in messages):")
  (println "```clojure")
  (println "{:role :tool")
  (println " :tool-call-id \"call_abc123\"  ; Must match the tool call ID")
  (println " :content \"result as string\"}")
  (println "```")
  
  (println "\nMessage Flow:")
  (println "1. User message → Model")
  (println "2. Model → Assistant message with :tool-calls")
  (println "3. Execute tools locally")
  (println "4. [User msg, Assistant msg with tool calls, Tool result(s)] → Model")
  (println "5. Model → Final assistant message"))

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
  
  ;; Show format first
  (format-demo-example)
  
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
  (format-demo-example)      ;; Shows format
  (simple-weather-example)   ;; Uses Anthropic
  (calculator-example)       ;; Uses Gemini
  (multi-tool-example)       ;; Uses Anthropic
  
  ;; Or run all
  (-main))
