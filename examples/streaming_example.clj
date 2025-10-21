(ns streaming-example
  "Examples demonstrating streaming API usage"
  (:require [litellm.core :as llm]
            [clojure.core.async :as async :refer [go-loop <!]]
            [litellm.streaming :as streaming]))

;; ============================================================================
;; Basic Streaming Example
;; ============================================================================

(defn basic-streaming-example
  "Basic example of streaming responses"
  []
  (println "=== Basic Streaming Example ===\n")
  
  ;; Create a streaming request
  (let [ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content "Count to 5 slowly"}]
             :stream true)]
    
    ;; Consume the channel
    (go-loop []
      (when-let [chunk (<! ch)]
        (if (streaming/is-error-chunk? chunk)
          (println "Error:" (:message chunk))
          (when-let [content (streaming/extract-content chunk)]
            (print content)
            (flush)))
        (recur)))
    
    (println "\n\nDone!")))

;; ============================================================================
;; Accumulating Streaming Example
;; ============================================================================

(defn accumulating-example
  "Example that accumulates the full response"
  []
  (println "=== Accumulating Streaming Example ===\n")
  
  (let [ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content "Write a haiku about Clojure"}]
             :stream true)]
    
    (go-loop [accumulated ""]
      (if-let [chunk (<! ch)]
        (if (streaming/is-error-chunk? chunk)
          (println "Error:" (:message chunk))
          (let [content (streaming/extract-content chunk)
                new-accumulated (str accumulated content)]
            (print content)
            (flush)
            (recur new-accumulated)))
        ;; Channel closed
        (do
          (println "\n\n---")
          (println "Full response length:" (count accumulated)))))))

;; ============================================================================
;; Multiple Requests Example
;; ============================================================================

(defn multiple-streams-example
  "Example showing multiple concurrent streams"
  []
  (println "=== Multiple Concurrent Streams ===\n")
  
  (let [questions ["What is Clojure?"
                   "What are monads?"
                   "Explain functional programming"]
        channels (map (fn [q]
                       (llm/completion 
                         :model "openai/gpt-4"
                         :messages [{:role :user :content q}]
                         :stream true
                         :max-tokens 50))
                     questions)]
    
    ;; Process each stream
    (doseq [[i ch] (map-indexed vector channels)]
      (println "\nStream" (inc i) ":")
      (go-loop []
        (when-let [chunk (<! ch)]
          (when-let [content (streaming/extract-content chunk)]
            (print content)
            (flush))
          (recur))
        (println)))))

;; ============================================================================
;; Error Handling Example
;; ============================================================================

(defn error-handling-example
  "Example demonstrating error handling in streams"
  []
  (println "=== Error Handling Example ===\n")
  
  (let [ch (llm/completion 
             :model "openai/gpt-4"
             :messages [{:role :user :content "Hello"}]
             :stream true
             :api-key "invalid-key")]  ; Will cause error
    
    (go-loop []
      (when-let [chunk (<! ch)]
        (if (streaming/is-error-chunk? chunk)
          (do
            (println "Caught error:")
            (println "  Type:" (:error-type chunk))
            (println "  Message:" (:message chunk))
            (println "  Provider:" (:provider chunk)))
          (when-let [content (streaming/extract-content chunk)]
            (print content)
            (flush)))
        (recur)))))

;; ============================================================================
;; Filtering Stream Content
;; ============================================================================

(defn filtering-example
  "Example using stream utilities to filter content"
  []
  (println "=== Stream Filtering Example ===\n")
  
  (let [source (llm/completion 
                 :model "openai/gpt-4"
                 :messages [{:role :user :content "Generate numbers: 1, 2, 3, 4, 5"}]
                 :stream true)
        ;; Only keep chunks with content
        filtered (streaming/filter-stream 
                   source 
                   #(some? (streaming/extract-content %)))]
    
    (go-loop []
      (when-let [chunk (<! filtered)]
        (println "Chunk:" (streaming/extract-content chunk))
        (recur)))))

;; ============================================================================
;; Usage Instructions
;; ============================================================================

(comment
  ;; Run individual examples:
  (basic-streaming-example)
  (accumulating-example)
  (multiple-streams-example)
  (error-handling-example)
  (filtering-example)
  
  ;; Remember to set your API key:
  ;; export OPENAI_API_KEY="your-key-here"
  
  ;; Or pass it explicitly:
  (llm/completion 
    :model "openai/gpt-4"
    :messages [{:role :user :content "Hello"}]
    :stream true
    :api-key "your-key"))
