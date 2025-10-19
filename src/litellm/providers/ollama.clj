(ns litellm.providers.ollama
  "Ollama provider implementation for LiteLLM"
  (:require [litellm.providers.core :as core]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]))

;; ============================================================================
;; Message Transformations
;; ============================================================================

(defn transform-messages-for-chat
  "Transform messages to Ollama chat format"
  [messages]
  (mapv (fn [msg]
          {:role (name (:role msg))
           :content (:content msg)})
        messages))

(defn transform-messages-for-generate
  "Transform messages to Ollama generate format (single prompt)"
  [messages]
  ;; For generate endpoint, we need to combine all messages into a single prompt
  (let [formatted-messages (map (fn [msg]
                                  (str (str/upper-case (name (:role msg))) ": " (:content msg)))
                                messages)]
    (str/join "\n\n" formatted-messages)))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-chat-response
  "Transform Ollama chat response to standard format"
  [response]
  (let [body (:body response)
        message (get-in body [:message])]
    {:id (str "ollama-" (java.util.UUID/randomUUID))
     :object "chat.completion"
     :created (quot (System/currentTimeMillis) 1000)
     :model (get body :model)
     :choices [{:index 0
                :message {:role :assistant
                          :content (:content message)}
                :finish-reason :stop}]
     :usage {:prompt-tokens (get-in body [:prompt_eval_count] 0)
             :completion-tokens (get-in body [:eval_count] 0)
             :total-tokens (+ (get-in body [:prompt_eval_count] 0)
                             (get-in body [:eval_count] 0))}}))

(defn transform-generate-response
  "Transform Ollama generate response to standard format"
  [response]
  (let [body (:body response)]
    {:id (str "ollama-" (java.util.UUID/randomUUID))
     :object "chat.completion"
     :created (quot (System/currentTimeMillis) 1000)
     :model (get body :model)
     :choices [{:index 0
                :message {:role :assistant
                          :content (:response body)}
                :finish-reason :stop}]
     :usage {:prompt-tokens (get body :prompt_eval_count 0)
             :completion-tokens (get body :eval_count 0)
             :total-tokens (+ (get body :prompt_eval_count 0)
                             (get body :eval_count 0))}}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle Ollama API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-msg (or (:error body) "Unknown error")]
    
    (case status
      404 (throw (ex-info "Model not found" 
                          {:type :model-not-found-error
                           :provider "ollama"
                           :model (:model body)}))
      (throw (ex-info error-msg
                      {:type :provider-error
                       :provider "ollama"
                       :status status
                       :data body})))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for Ollama models (in USD)
   Note: These are approximate and may vary as Ollama is typically run locally"
  {"llama2" {:input 0.0 :output 0.0}
   "llama2-uncensored" {:input 0.0 :output 0.0}
   "llama2-13b" {:input 0.0 :output 0.0}
   "llama2-70b" {:input 0.0 :output 0.0}
   "llama2-7b" {:input 0.0 :output 0.0}
   "llama3" {:input 0.0 :output 0.0}
   "mistral" {:input 0.0 :output 0.0}
   "mistral-7b-instruct-v0.1" {:input 0.0 :output 0.0}
   "mistral-7b-instruct-v0.2" {:input 0.0 :output 0.0}
   "mixtral-8x7b-instruct-v0.1" {:input 0.0 :output 0.0}
   "mixtral-8x22b-instruct-v0.1" {:input 0.0 :output 0.0}
   "codellama" {:input 0.0 :output 0.0}
   "orca-mini" {:input 0.0 :output 0.0}
   "vicuna" {:input 0.0 :output 0.0}
   "nous-hermes" {:input 0.0 :output 0.0}
   "nous-hermes-13b" {:input 0.0 :output 0.0}
   "wizard-vicuna-uncensored" {:input 0.0 :output 0.0}
   "llava" {:input 0.0 :output 0.0}})

;; ============================================================================
;; Ollama Provider Multimethod Implementations
;; ============================================================================

(defmethod core/transform-request :ollama
  [_ request config]
  (let [original-model (:model request)
        is-chat (str/starts-with? original-model "ollama_chat/")
        model (core/extract-model-name original-model)
        actual-model (if is-chat 
                       (if (str/starts-with? original-model "ollama_chat/")
                         (subs original-model (count "ollama_chat/"))
                         model)
                       model)
        messages (:messages request)]
    
    (if is-chat
      ;; Chat API format
      (cond-> {:model actual-model
               :messages (transform-messages-for-chat messages)
               :stream (:stream request false)}
        (:format request) (assoc :format (:format request)))
      
      ;; Generate API format
      (cond-> {:model actual-model
               :prompt (transform-messages-for-generate messages)
               :stream (:stream request false)
               :options {:num_predict (or (:max-tokens request) 128)
                        :temperature (or (:temperature request) 0.7)
                        :top_p (or (:top-p request) 1.0)}}
        (:format request) (assoc :format (:format request))))))

(defmethod core/make-request :ollama
  [_ transformed-request thread-pools telemetry config]
  (let [model (:model transformed-request)
        is-chat (contains? transformed-request :messages)
        url (str (:api-base config "http://localhost:11434") (if is-chat "/api/chat" "/api/generate"))]
    
    (cp/future (:api-calls thread-pools)
      (let [start-time (System/currentTimeMillis)
            response (http/post url
                                {:headers {"Content-Type" "application/json"
                                           "User-Agent" "litellm-clj/1.0.0"}
                                 :body (json/encode transformed-request)
                                 :timeout (:timeout config 30000)
                                 :as :json})
            duration (- (System/currentTimeMillis) start-time)]
        
        ;; Handle errors
        (when (>= (:status response) 400)
          (handle-error-response :ollama response))
        
        ;; Add request type to response for later processing
        (assoc response :ollama-request-type (if is-chat :chat :generate))))))

(defmethod core/transform-response :ollama
  [_ response]
  (let [request-type (:ollama-request-type response)]
    (case request-type
      :chat (transform-chat-response response)
      :generate (transform-generate-response response)
      ;; Default case
      (transform-generate-response response))))

(defmethod core/supports-streaming? :ollama [_] true)

(defmethod core/supports-function-calling? :ollama [_] false)

(defmethod core/get-rate-limits :ollama [_]
  {:requests-per-minute 60
   :tokens-per-minute 100000})

(defmethod core/health-check :ollama
  [_ thread-pools config]
  (cp/future (:health-checks thread-pools)
    (try
      (let [response (http/get (str (:api-base config "http://localhost:11434") "/api/tags")
                              {:timeout 5000})]
        (= 200 (:status response)))
      (catch Exception e
        (log/warn "Ollama health check failed" {:error (.getMessage e)})
        false))))

(defmethod core/get-cost-per-token :ollama
  [_ model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defn parse-streaming-chunk
  "Parse a streaming chunk from Ollama"
  [chunk request-type]
  (case request-type
    :chat
    (let [message (get-in chunk [:message])]
      {:id (str "ollama-" (java.util.UUID/randomUUID))
       :object "chat.completion.chunk"
       :created (quot (System/currentTimeMillis) 1000)
       :model (get chunk :model)
       :choices [{:index 0
                  :delta {:role :assistant
                         :content (:content message)}
                  :finish-reason (when (:done chunk) :stop)}]})
    
    :generate
    {:id (str "ollama-" (java.util.UUID/randomUUID))
     :object "chat.completion.chunk"
     :created (quot (System/currentTimeMillis) 1000)
     :model (get chunk :model)
     :choices [{:index 0
                :delta {:role :assistant
                       :content (:response chunk)}
                :finish-reason (when (:done chunk) :stop)}]}))

(defn handle-streaming-response
  "Handle streaming response from Ollama"
  [response callback]
  (let [body (:body response)
        request-type (:ollama-request-type response)]
    (doseq [line (str/split-lines body)]
      (when-not (str/blank? line)
        (try
          (let [chunk (json/decode line true)
                transformed (parse-streaming-chunk chunk request-type)]
            (callback transformed))
          (catch Exception e
            (log/debug "Failed to parse streaming chunk" {:line line :error (.getMessage e)})))))))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available Ollama models"
  [provider]
  (try
    (let [response (http/get (str (:api-base provider) "/api/tags")
                            {:as :json})]
      (if (= 200 (:status response))
        (map :name (get-in response [:body :models]))
        (throw (ex-info "Failed to list models" {:status (:status response)}))))
    (catch Exception e
      (log/error "Error listing Ollama models" e)
      [])))

;; ============================================================================
;; Provider Testing
;; ============================================================================

(defn test-ollama-connection
  "Test Ollama connection with a simple request"
  [provider thread-pools telemetry]
  (let [test-request {:model "llama2"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (core/transform-request provider test-request)
            response-future (core/make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (core/transform-response provider response)]
        {:success true
         :provider "ollama"
         :model "llama2"
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "ollama"
         :error (.getMessage e)
         :error-type (type e)}))))
