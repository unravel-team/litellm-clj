(ns streaming-example
  "Examples demonstrating streaming API usage"
  (:require [litellm.router :as router]
            [clojure.core.async :as async :refer [go-loop <!]]
            [litellm.streaming :as streaming]))

;; ============================================================================
;; Setup
;; ============================================================================

;; One-time setup - registers configs from environment variables
(defn setup! []
  (router/quick-setup!))

;; ============================================================================
;; Basic Streaming Example
;; ============================================================================

(defn basic-streaming-example
  "Basic example of streaming responses"
  []
  (println "=== Basic Streaming Example ===\n")
  
  ;; Register a config if not already set up
  (when-not (seq (router/list-configs))
    (setup!))
  
  ;; Create a streaming request using router
  (let [ch (router/completion :openai
                              {:messages [{:role :user :content "Count to 5 slowly"}]
                               :stream true})]
    
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
  
  (when-not (seq (router/list-configs))
    (setup!))
  
  (let [ch (router/completion :openai
                              {:messages [{:role :user :content "Write a haiku about Clojure"}]
                               :stream true})]
    
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
  
  (when-not (seq (router/list-configs))
    (setup!))
  
  (let [questions ["What is Clojure?"
                   "What are monads?"
                   "Explain functional programming"]
        channels (map (fn [q]
                       (router/completion :openai
                                          {:messages [{:role :user :content q}]
                                           :stream true
                                           :max-tokens 50}))
                     questions)]
    
    ;; Process each stream
    (doseq [[i ch] (map-indexed vector channels)]
      (println "\nStream" (inc i) ":")
      (go-loop []
        (if-let [chunk (<! ch)]
          (do
            (when-let [content (streaming/extract-content chunk)]
              (print content)
              (flush))
            (recur))
          (println))))))

;; ============================================================================
;; Error Handling Example
;; ============================================================================

(defn error-handling-example
  "Example demonstrating error handling in streams"
  []
  (println "=== Error Handling Example ===\n")
  
  ;; Register with invalid key to demonstrate error handling
  (router/register! :invalid
    {:provider :openai
     :model "gpt-4"
     :config {:api-key "invalid-key"}})
  
  (let [ch (router/completion :invalid
                              {:messages [{:role :user :content "Hello"}]
                               :stream true})]  ; Will cause error
    
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
  "Example using core.async to filter content"
  []
  (println "=== Stream Filtering Example ===\n")
  
  (when-not (seq (router/list-configs))
    (setup!))
  
  (let [source (router/completion :openai
                                  {:messages [{:role :user :content "Generate numbers: 1, 2, 3, 4, 5"}]
                                   :stream true})]
    
    ;; Filter and process chunks using core.async
    (go-loop []
      (when-let [chunk (<! source)]
        ;; Only process chunks that have content
        (when-let [content (streaming/extract-content chunk)]
          (when (not (clojure.string/blank? content))
            (println "Chunk:" content)))
        (recur)))))

;; ============================================================================
;; Usage Instructions
;; ============================================================================

(comment
  ;; First, set up the router (reads from environment variables)
  (setup!)
  
  ;; Run individual examples:
  (basic-streaming-example)
  (accumulating-example)
  (multiple-streams-example)
  (error-handling-example)
  (filtering-example)
)
