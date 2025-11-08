(ns litellm.examples.embeddings
  "Examples demonstrating the embeddings API across different providers"
  (:require [litellm.core :as core]
            [litellm.router :as router]
            [clojure.pprint :as pprint]))

;; ============================================================================
;; OpenAI Embeddings Examples
;; ============================================================================

(defn example-openai-single-embedding
  "Generate embeddings for a single text using OpenAI"
  []
  (println "\n=== OpenAI Single Text Embedding ===")
  
  (try
    ;; Setup provider using router
    (router/setup-openai! :model "text-embedding-3-small")
    
    (let [config (router/get-config :openai)
          request {:input "The quick brown fox jumps over the lazy dog"}
          
          response (core/embedding (:provider config) 
                                   (:model config)
                                   request 
                                   (:config config))]
      
      (println "Input text:" (:input request))
      (println "\nEmbedding dimensions:" (count (get-in response [:data 0 :embedding])))
      (println "Tokens used:" (get-in response [:usage :total-tokens]))
      (println "\nFirst 5 dimensions:" (take 5 (get-in response [:data 0 :embedding]))))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

(defn example-openai-batch-embeddings
  "Generate embeddings for multiple texts using OpenAI"
  []
  (println "\n=== OpenAI Batch Embeddings ===")
  
  (try
    (let [texts ["Hello world"
                 "Clojure is a functional programming language"
                 "LiteLLM makes it easy to work with LLMs"]
          
          config (router/get-config :openai)
          request {:input texts}
          
          response (core/embedding (:provider config)
                                   (:model config)
                                   request
                                   (:config config))]
      
      (println "Number of texts:" (count texts))
      (println "Number of embeddings:" (count (:data response)))
      (println "Tokens used:" (get-in response [:usage :total-tokens]))
      
      (doseq [[idx text] (map-indexed vector texts)]
        (let [embedding (get-in response [:data idx :embedding])]
          (println (format "\nText %d: \"%s\"" (inc idx) text))
          (println (format "  Embedding dimensions: %d" (count embedding)))
          (println (format "  First 3 values: %s" (take 3 embedding))))))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

(defn example-openai-with-dimensions
  "Generate embeddings with custom dimensions (OpenAI only)"
  []
  (println "\n=== OpenAI Embeddings with Custom Dimensions ===")
  
  (try
    (let [config (router/get-config :openai)
          request {:input "Embedding with reduced dimensions"
                   :dimensions 512}  ; text-embedding-3-small supports up to 1536
          
          response (core/embedding (:provider config)
                                   (:model config)
                                   request
                                   (:config config))]
      
      (println "Requested dimensions:" (:dimensions request))
      (println "Actual dimensions:" (count (get-in response [:data 0 :embedding])))
      (println "Tokens used:" (get-in response [:usage :total-tokens])))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

;; ============================================================================
;; Mistral Embeddings Examples
;; ============================================================================

(defn example-mistral-embedding
  "Generate embeddings using Mistral AI"
  []
  (println "\n=== Mistral Embeddings ===")
  
  (try
    ;; Setup Mistral provider
    (router/setup-mistral! :model "mistral-embed")
    
    (let [config (router/get-config :mistral)
          request {:input "Mistral AI provides powerful embedding models"}
          
          response (core/embedding (:provider config)
                                   (:model config)
                                   request
                                   (:config config))]
      
      (println "Model:" (get-in response [:model]))
      (println "Embedding dimensions:" (count (get-in response [:data 0 :embedding])))
      (println "Tokens used:" (get-in response [:usage :total-tokens])))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

;; ============================================================================
;; Google Gemini Embeddings Examples
;; ============================================================================

(defn example-gemini-embedding
  "Generate embeddings using Google Gemini"
  []
  (println "\n=== Gemini Embeddings ===")
  
  (try
    ;; Setup Gemini provider
    (router/setup-gemini! :model "text-embedding-004")
    
    (let [config (router/get-config :gemini)
          request {:input "Google Gemini embeddings for semantic search"}
          
          response (core/embedding (:provider config)
                                   (:model config)
                                   request
                                   (:config config))]
      
      (println "Model:" (get-in response [:model]))
      (println "Embedding dimensions:" (count (get-in response [:data 0 :embedding])))
      (println "First 5 dimensions:" (take 5 (get-in response [:data 0 :embedding]))))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

;; ============================================================================
;; Provider-Specific Convenience Functions
;; ============================================================================

(defn example-convenience-functions
  "Demonstrate provider-specific convenience functions"
  []
  (println "\n=== Provider Convenience Functions ===")
  
  ;; OpenAI
  (when-let [api-key (System/getenv "OPENAI_API_KEY")]
    (try
      (println "\nUsing openai-embedding convenience function:")
      (let [response (core/openai-embedding 
                       "text-embedding-3-small"
                       {:input "Quick test with OpenAI"}
                       :api-key api-key)]
        (println "  Dimensions:" (count (get-in response [:data 0 :embedding]))))
      (catch Exception e
        (println "  Error:" (.getMessage e)))))
  
  ;; Mistral
  (when-let [api-key (System/getenv "MISTRAL_API_KEY")]
    (try
      (println "\nUsing mistral-embedding convenience function:")
      (let [response (core/mistral-embedding 
                       "mistral-embed"
                       {:input "Quick test with Mistral"}
                       :api-key api-key)]
        (println "  Dimensions:" (count (get-in response [:data 0 :embedding]))))
      (catch Exception e
        (println "  Error:" (.getMessage e)))))
  
  ;; Gemini
  (when-let [api-key (System/getenv "GOOGLE_API_KEY")]
    (try
      (println "\nUsing gemini-embedding convenience function:")
      (let [response (core/gemini-embedding 
                       "text-embedding-004"
                       {:input "Quick test with Gemini"}
                       :api-key api-key)]
        (println "  Dimensions:" (count (get-in response [:data 0 :embedding]))))
      (catch Exception e
        (println "  Error:" (.getMessage e))))))

;; ============================================================================
;; Practical Use Cases
;; ============================================================================

(defn cosine-similarity
  "Calculate cosine similarity between two vectors"
  [v1 v2]
  (let [dot-product (reduce + (map * v1 v2))
        magnitude1 (Math/sqrt (reduce + (map #(* % %) v1)))
        magnitude2 (Math/sqrt (reduce + (map #(* % %) v2)))]
    (/ dot-product (* magnitude1 magnitude2))))

(defn example-semantic-similarity
  "Demonstrate semantic similarity using embeddings"
  []
  (println "\n=== Semantic Similarity Example ===")
  
  (try
    (let [texts ["The cat sat on the mat"
                 "A feline rested on a rug"
                 "Python is a programming language"]
          
          config (router/get-config :openai)
          request {:input texts}
          
          response (core/embedding (:provider config)
                                   (:model config)
                                   request
                                   (:config config))
          embeddings (map :embedding (:data response))]
      
      (println "Comparing semantic similarity:\n")
      
      ;; Compare first two sentences (semantically similar)
      (let [sim1 (cosine-similarity (nth embeddings 0) (nth embeddings 1))]
        (println (format "Text 1: \"%s\"" (nth texts 0)))
        (println (format "Text 2: \"%s\"" (nth texts 1)))
        (println (format "Similarity: %.4f (should be high)\n" sim1)))
      
      ;; Compare first and third sentences (semantically different)
      (let [sim2 (cosine-similarity (nth embeddings 0) (nth embeddings 2))]
        (println (format "Text 1: \"%s\"" (nth texts 0)))
        (println (format "Text 3: \"%s\"" (nth texts 2)))
        (println (format "Similarity: %.4f (should be low)\n" sim2))))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

(defn example-semantic-search
  "Demonstrate basic semantic search using embeddings"
  []
  (println "\n=== Semantic Search Example ===")
  
  (try
    (let [documents ["Clojure is a functional programming language"
                     "Java is an object-oriented language"
                     "Python is great for data science"
                     "Lisp influenced many modern languages"
                     "Machine learning models need lots of data"]
          
          query "Tell me about functional programming"
          
          config (router/get-config :openai)
          
          ;; Get embeddings for all documents
          doc-response (core/embedding (:provider config)
                                       (:model config)
                                       {:input documents}
                                       (:config config))
          
          ;; Get embedding for query
          query-response (core/embedding (:provider config)
                                         (:model config)
                                         {:input query}
                                         (:config config))
          
          doc-embeddings (map :embedding (:data doc-response))
          query-embedding (get-in query-response [:data 0 :embedding])
          
          ;; Calculate similarities
          similarities (map #(cosine-similarity query-embedding %) doc-embeddings)
          
          ;; Rank documents by similarity
          ranked-docs (sort-by second > (map vector documents similarities))]
      
      (println "Query:" query)
      (println "\nRanked results:")
      (doseq [[idx [doc score]] (map-indexed vector (take 3 ranked-docs))]
        (println (format "\n%d. Score: %.4f" (inc idx) score))
        (println (format "   \"%s\"" doc))))
    
    (catch Exception e
      (println "Error:" (.getMessage e)))))

;; ============================================================================
;; Error Handling Examples
;; ============================================================================

(defn example-error-handling
  "Demonstrate error handling with embeddings"
  []
  (println "\n=== Error Handling Examples ===")
  
  ;; Invalid provider
  (try
    (println "\n1. Testing unsupported provider:")
    (core/embedding :anthropic "claude-3-sonnet-20240229"
                    {:input "This will fail"}
                    {:api-key "dummy"})
    (catch Exception e
      (println "  Caught expected error:" (.getMessage e))))
  
  ;; Missing required field
  (try
    (println "\n2. Testing missing input field:")
    (core/embedding :openai "text-embedding-3-small"
                    {}
                    {:api-key "dummy"})
    (catch Exception e
      (println "  Caught expected error:" (.getMessage e))))
  
  ;; Invalid API key
  (try
    (println "\n3. Testing invalid API key:")
    (core/embedding :openai "text-embedding-3-small"
                    {:input "Test"}
                    {:api-key "invalid-key"})
    (catch Exception e
      (println "  Caught expected error:" (.getMessage e)))))

;; ============================================================================
;; Main Runner
;; ============================================================================

(defn run-all-examples
  "Run all embedding examples"
  []
  (println "╔════════════════════════════════════════╗")
  (println "║   LiteLLM Embeddings API Examples     ║")
  (println "╚════════════════════════════════════════╝")
  
  ;; Check for API keys
  (println "\nAPI Keys Status:")
  (println "  OpenAI:" (if (System/getenv "OPENAI_API_KEY") "✓" "✗"))
  (println "  Mistral:" (if (System/getenv "MISTRAL_API_KEY") "✓" "✗"))
  (println "  Google:" (if (System/getenv "GOOGLE_API_KEY") "✓" "✗"))
  
  ;; Setup providers using router
  (println "\nSetting up providers...")
  (router/quick-setup!)
  
  ;; Update embedding models for providers
  (when (System/getenv "OPENAI_API_KEY")
    (router/setup-openai! :model "text-embedding-3-small"))
  
  (when (System/getenv "MISTRAL_API_KEY")
    (router/setup-mistral! :model "mistral-embed"))
  
  (when (System/getenv "GOOGLE_API_KEY")
    (router/setup-gemini! :model "text-embedding-004"))
  
  (println "Registered configs:" (router/list-configs))
  
  ;; Run examples
  (when (System/getenv "OPENAI_API_KEY")
    (example-openai-single-embedding)
    (example-openai-batch-embeddings)
    (example-openai-with-dimensions)
    (example-semantic-similarity)
    (example-semantic-search))
  
  (when (System/getenv "MISTRAL_API_KEY")
    (example-mistral-embedding))
  
  (when (System/getenv "GOOGLE_API_KEY")
    (example-gemini-embedding))
  
  (example-convenience-functions)
  (example-error-handling)
  
  (println "\n✓ All examples completed!"))

;; ============================================================================
;; REPL Usage
;; ============================================================================

(comment
  ;; Set your API keys first:
  ;; export OPENAI_API_KEY="your-openai-key"
  ;; export MISTRAL_API_KEY="your-mistral-key"
  ;; export GOOGLE_API_KEY="your-google-key"
  
  ;; Run individual examples:
  (example-openai-single-embedding)
  (example-openai-batch-embeddings)
  (example-openai-with-dimensions)
  (example-mistral-embedding)
  (example-gemini-embedding)
  (example-convenience-functions)
  (example-semantic-similarity)
  (example-semantic-search)
  (example-error-handling)
  
  ;; Or run all examples:
  (run-all-examples)
  )
