(ns litellm.examples.basic-usage
  "Basic usage examples for the LiteLLM Clojure library"
  (:require [litellm.core :as litellm]
            [clojure.pprint :as pprint]))

(defn example-openai-completion
  "Example of making a completion request to OpenAI"
  []
  (println "=== OpenAI Completion Example ===")
  
  ;; Start the LiteLLM system
  (let []
    
    (try
      ;; Make a completion request
      (let [request {:model "gpt-3.5-turbo"
                     :messages [{:role :user 
                                 :content "What is the capital of France?"}]
                     :api-key "sk-" 
                     :max_tokens 100
                     :temperature 0.7}
            
            response (litellm/completion request)]
        
        (println "Request:")
        (pprint/pprint request)
        (println "\nResponse:")
        (pprint/pprint response)
        
        ;; Extract the actual message content
        (when-let [content (-> response :choices first :message :content)]
          (println "\nAI Response:" content)))
      
      (catch Exception e
        (println "Error occurred:" (.getMessage e))
        (pprint/pprint (ex-data e)))
      
      (finally
        ;; Stop the system
        (println "\nSystem stopped.")))))

(defn example-with-streaming
  "Example of streaming completion (when implemented)"
  []
  (println "\n=== Streaming Example (Future Implementation) ===")
  (println "Streaming support will be added in future versions"))

(defn example-multiple-providers
  "Example showing how to use multiple providers (when implemented)"
  []
  (println "\n=== Multiple Providers Example (Future Implementation) ===")
  (println "Additional providers (Anthropic, Azure, etc.) will be added"))

(defn -main
  "Run all examples"
  [& args]
  (println "LiteLLM Clojure Library Examples")
  (println "================================")
  
  ;; Check if OpenAI API key is set
  (if (System/getenv "OPENAI_API_KEY")
    (do
      (example-openai-completion)
      (example-with-streaming)
      (example-multiple-providers))
    (println "Please set OPENAI_API_KEY environment variable to run examples"))
  
  (println "\nExamples completed!"))

;; For REPL usage
(comment
  ;; Set your OpenAI API key first:
  ;; export OPENAI_API_KEY="your-api-key-here"
  
  ;; Then run the example:
  (example-openai-completion)
  
  ;; Or run all examples:
  (-main))
