# Examples

A collection of practical examples for common LiteLLM use cases.

## Basic Usage

### Simple Question & Answer

```clojure
(require '[litellm.router :as router])

(router/quick-setup!)

(defn ask [question]
  (-> (router/chat :openai question)
      router/extract-content))

(ask "What is the capital of France?")
;; => "The capital of France is Paris."
```

### With System Prompt

```clojure
(defn ask-expert [question domain]
  (-> (router/chat :openai question
        :system-prompt (str "You are an expert in " domain))
      router/extract-content))

(ask-expert "Explain quantum entanglement" "physics")
```

## Multi-turn Conversations

### Building a Conversation

```clojure
(require '[litellm.core :as core])

(defn chat-session []
  (let [history (atom [{:role :system :content "You are a helpful assistant"}])]
    
    (fn [user-message]
      (swap! history conj {:role :user :content user-message})
      
      (let [response (core/completion :openai "gpt-4"
                       {:messages @history}
                       {:api-key (System/getenv "OPENAI_API_KEY")})
            assistant-message (core/extract-message response)]
        
        (swap! history conj assistant-message)
        (:content assistant-message)))))

;; Usage
(def chat (chat-session))
(chat "Hi, I'm learning Clojure")
;; => "Great! Clojure is a powerful functional programming language..."
(chat "What's a good first project?")
;; => "For beginners, I'd recommend starting with..."
```

## Streaming Examples

### Progressive CLI Output

```clojure
(require '[litellm.core :as core]
         '[litellm.streaming :as streaming]
         '[clojure.core.async :refer [go-loop <!]])

(defn streaming-chat [question]
  (let [ch (core/completion :openai "gpt-4"
             {:messages [{:role :user :content question}]
              :stream true}
             {:api-key (System/getenv "OPENAI_API_KEY")})]
    
    (streaming/consume-stream-with-callbacks ch
      (fn [chunk]
        (print (streaming/extract-content chunk))
        (flush))
      (fn [_] (println))
      (fn [error] (println "Error:" error)))))

(streaming-chat "Write a short poem about Clojure")
```


## Next Steps

- Review the [[API Guide|api-guide]] for detailed API documentation
- Check [[Core API|core-api]] for direct provider access
- Explore [[Router API|router-api]] for configuration management
- Learn about [[streaming|streaming]] responses
- Read about [[error handling|/docs/ERROR_HANDLING.md]]
