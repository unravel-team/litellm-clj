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
      (let [request {:messages [{:role :user 
                                 :content "What is the capital of India?"}]
                     :api-key (System/getenv "OPENAI_API_KEY")
                     :max-tokens 100
            
            response (litellm/completion :openai "gpt-4o-mini"
                                 request)]
        
        (println "Request:")
        (pprint/pprint request)
        (println "\nResponse:")
        (pprint/pprint response)
        
        ;; Extract the actual message content
        (when-let [content (-> response :choices first :message :content)]
          (println "\nAI Response:" content)))
      
      (catch Exception e
        (println "Error occurred:" (.getMessage e))
        (pprint/pprint (ex-data e))))))


;; For REPL usage
(comment
  ;; Set your OpenAI API key first:
  ;; export OPENAI_API_KEY="your-api-key-here"
  
  ;; Then run the example:
  (example-openai-completion)
  
  )
