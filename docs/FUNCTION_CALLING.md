# Function Calling Guide

This guide explains how to use function calling (also known as tool use) with LiteLLM. Function calling allows AI models to request execution of predefined functions, enabling them to interact with external systems, APIs, and data sources.

## Overview

Function calling is a powerful feature that enables models to:
- Call external APIs and services
- Perform calculations
- Query databases
- Access real-time information
- Execute custom business logic

## Supported Providers

| Provider | Function Calling Support | Streaming Support |
|----------|-------------------------|-------------------|
| Anthropic | ✅ Yes | ✅ Yes |
| Gemini | ✅ Yes | ✅ Yes |
| OpenAI | ✅ Yes | ✅ Yes |
| Mistral | ❌ No | N/A |
| Ollama | ❌ No | N/A |
| OpenRouter | ❌ No | N/A |

## Basic Usage

### 1. Define Your Tools

Tools are defined using a standard format that describes the function, its parameters, and their types:

```clojure
(def weather-tool
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
```

### 2. Make a Request with Tools

Include the tools in your chat completion request:

```clojure
(require '[litellm.core :as litellm])

(def response
  (litellm/chat-completion
    {:provider :anthropic
     :model "claude-3-sonnet"
     :messages [{:role :user 
                 :content "What's the weather like in San Francisco?"}]
     :tools [weather-tool]
     :tool-choice :auto}))
```

### 3. Check for Tool Calls

The model may request one or more tool calls in its response:

```clojure
(def tool-calls (get-in response [:choices 0 :message :tool-calls]))

(when (seq tool-calls)
  (println "Model requested tool calls:" tool-calls))
```

### 4. Execute Tools and Send Results

Execute the requested tools and send the results back to the model:

```clojure
;; Execute the tool
(defn execute-tool [tool-call]
  (let [function-name (get-in tool-call [:function :name])
        arguments (json/decode (get-in tool-call [:function :arguments]) true)]
    ;; Your tool implementation here
    (get-weather (:location arguments) (:unit arguments))))

;; Create tool result messages
(def tool-results
  (map (fn [tool-call]
         {:role :tool
          :tool-call-id (:id tool-call)
          :content (execute-tool tool-call)})
       tool-calls))

;; Continue the conversation with tool results
(def final-response
  (litellm/chat-completion
    {:provider :anthropic
     :model "claude-3-sonnet"
     :messages (concat
                 [{:role :user :content "What's the weather like in San Francisco?"}]
                 [(get-in response [:choices 0 :message])]
                 tool-results)}))
```

## Tool Choice Options

Control how the model uses tools with the `:tool-choice` parameter:

### `:auto` (Default)
The model decides whether to call a tool based on the context:

```clojure
{:tools [weather-tool]
 :tool-choice :auto}
```

### `:none`
Prevent the model from calling any tools:

```clojure
{:tools [weather-tool]
 :tool-choice :none}
```

### `:any` / `:required`
Force the model to call at least one tool:

```clojure
{:tools [weather-tool]
 :tool-choice :any}
```

### Specific Tool
Force the model to use a specific tool:

```clojure
{:tools [weather-tool calculator-tool]
 :tool-choice {:type "function" :name "get_weather"}}
```

## Complete Example

Here's a complete example with a weather tool:

```clojure
(ns my-app.function-calling
  (:require [litellm.core :as litellm]
            [cheshire.core :as json]))

;; Define the tool
(def weather-tool
  {:type "function"
   :function {:name "get_weather"
             :description "Get the current weather for a location"
             :parameters {:type "object"
                         :properties {:location {:type "string"
                                                :description "The city and state"}
                                     :unit {:type "string"
                                           :enum ["celsius" "fahrenheit"]}}
                         :required ["location"]}}})

;; Implement the tool
(defn get-weather [location unit]
  (json/encode {:location location
                :temperature (if (= unit "celsius") 22 72)
                :unit unit
                :condition "sunny"}))

;; Execute tool calls
(defn execute-tool-call [tool-call]
  (let [name (get-in tool-call [:function :name])
        args (json/decode (get-in tool-call [:function :arguments]) true)]
    (case name
      "get_weather" (get-weather (:location args) (:unit args))
      (json/encode {:error "Unknown function"}))))

;; Main conversation loop
(defn weather-conversation []
  ;; Step 1: Initial request
  (let [response (litellm/chat-completion
                   {:provider :anthropic
                    :model "claude-3-sonnet"
                    :messages [{:role :user 
                               :content "What's the weather in Tokyo?"}]
                    :tools [weather-tool]
                    :tool-choice :auto})
        
        tool-calls (get-in response [:choices 0 :message :tool-calls])]
    
    ;; Step 2: Execute tools if requested
    (if (seq tool-calls)
      (let [tool-results (map (fn [call]
                                {:role :tool
                                 :tool-call-id (:id call)
                                 :content (execute-tool-call call)})
                              tool-calls)
            
            ;; Step 3: Send results back
            final-response (litellm/chat-completion
                             {:provider :anthropic
                              :model "claude-3-sonnet"
                              :messages [{:role :user :content "What's the weather in Tokyo?"}
                                        (get-in response [:choices 0 :message])
                                        (first tool-results)]})]
        
        (get-in final-response [:choices 0 :message :content]))
      
      ;; No tool calls, return the response
      (get-in response [:choices 0 :message :content]))))
```

## Provider-Specific Notes

### Anthropic (Claude)

- Supports all tool choice options
- Tool results are sent as user messages with `tool_result` content type
- Streaming includes tool call chunks
- Uses the `tools` parameter (not `functions`)

**API Version:** Requires `anthropic-version: 2023-06-01` or later

### Gemini

- Supports all tool choice options
- Native function calling support
- Streaming includes function call deltas
- Tool results are sent with `tool` role

**Models:** All Gemini 1.5+ models support function calling

### OpenAI

- Industry-standard function calling implementation
- Supports both `tools` (recommended) and legacy `functions` parameter
- Streaming includes tool call deltas
- Most models support function calling (GPT-3.5-Turbo and above)

## Multi-Turn Conversations

Function calling often requires multiple turns. Here's a pattern for handling multi-turn conversations:

```clojure
(defn conversation-loop [initial-message tools]
  (loop [messages [{:role :user :content initial-message}]
         turn 1
         max-turns 5]
    (when (<= turn max-turns)
      (let [response (litellm/chat-completion
                       {:provider :anthropic
                        :model "claude-3-sonnet"
                        :messages messages
                        :tools tools
                        :tool-choice :auto})
            
            assistant-msg (get-in response [:choices 0 :message])
            tool-calls (:tool-calls assistant-msg)]
        
        (if (seq tool-calls)
          ;; Execute tools and continue
          (let [results (map execute-tool-call tool-calls)]
            (recur (concat messages [assistant-msg] results)
                   (inc turn)
                   max-turns))
          
          ;; No more tool calls, return final response
          (:content assistant-msg))))))
```

## Best Practices

### 1. Clear Tool Descriptions

Provide clear, detailed descriptions of what each tool does:

```clojure
{:name "search_products"
 :description "Search the product catalog by name, category, or price range. Returns a list of matching products with details including name, price, availability, and ratings."
 :parameters {...}}
```

### 2. Validate Parameters

Use JSON Schema validation for tool parameters:

```clojure
{:type "object"
 :properties {:price {:type "number"
                     :minimum 0
                     :description "Price in USD"}
             :quantity {:type "integer"
                       :minimum 1
                       :maximum 100}}
 :required ["price" "quantity"]}
```

### 3. Handle Errors Gracefully

Return structured error messages that the model can understand:

```clojure
(defn execute-tool-call [tool-call]
  (try
    (let [result (call-actual-tool tool-call)]
      (json/encode {:success true :data result}))
    (catch Exception e
      (json/encode {:success false 
                    :error (.getMessage e)}))))
```

### 4. Limit Conversation Turns

Prevent infinite loops by limiting the number of turns:

```clojure
(loop [messages initial-messages
       turn 1]
  (when (<= turn 5)  ; Maximum 5 turns
    ...))
```

### 5. Use Specific Tool Choice When Needed

For critical operations, force tool usage:

```clojure
{:tools [validate-order-tool]
 :tool-choice {:type "function" :name "validate_order"}}
```

## Debugging

### Enable Logging

```clojure
(require '[clojure.tools.logging :as log])

;; Log tool calls
(when (seq tool-calls)
  (log/info "Tool calls requested:" tool-calls))

;; Log tool results
(log/info "Tool execution result:" result)
```

### Inspect Tool Call Structure

```clojure
(let [tool-call (first tool-calls)]
  (println "ID:" (:id tool-call))
  (println "Type:" (:type tool-call))
  (println "Function:" (get-in tool-call [:function :name]))
  (println "Arguments:" (get-in tool-call [:function :arguments])))
```

## Common Patterns

### Weather Service

```clojure
(def weather-tools
  [{:type "function"
    :function {:name "get_current_weather"
              :description "Get current weather"
              :parameters {:type "object"
                          :properties {:location {:type "string"}}
                          :required ["location"]}}}
   {:type "function"
    :function {:name "get_forecast"
              :description "Get weather forecast"
              :parameters {:type "object"
                          :properties {:location {:type "string"}
                                      :days {:type "integer" :minimum 1 :maximum 10}}
                          :required ["location" "days"]}}}])
```

### Database Query

```clojure
(def db-tools
  [{:type "function"
    :function {:name "query_database"
              :description "Query the database with SQL"
              :parameters {:type "object"
                          :properties {:query {:type "string"
                                              :description "SQL query to execute"}
                                      :limit {:type "integer"
                                             :default 100}}
                          :required ["query"]}}}])
```

### E-commerce

```clojure
(def ecommerce-tools
  [{:type "function"
    :function {:name "search_products"
              :description "Search for products"
              :parameters {:type "object"
                          :properties {:query {:type "string"}
                                      :category {:type "string"}
                                      :max_price {:type "number"}}}}}
   {:type "function"
    :function {:name "get_product_details"
              :description "Get detailed information about a specific product"
              :parameters {:type "object"
                          :properties {:product_id {:type "string"}}
                          :required ["product_id"]}}}
   {:type "function"
    :function {:name "add_to_cart"
              :description "Add a product to the shopping cart"
              :parameters {:type "object"
                          :properties {:product_id {:type "string"}
                                      :quantity {:type "integer" :minimum 1}}
                          :required ["product_id" "quantity"]}}}])
```

## See Also

- [API Guide](API_GUIDE.md) - Complete API reference
- [Provider Examples](../examples/provider_examples.clj) - Provider-specific examples
- [Function Calling Examples](../examples/function_calling_example.clj) - Working examples
