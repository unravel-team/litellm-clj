(ns litellm.providers.gemini
  "Google Gemini provider implementation for LiteLLM"
  (:require [litellm.streaming :as streaming]
            [litellm.errors :as errors]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.async :as async :refer [go >!]]))

;; ============================================================================
;; Message Transformations
;; ============================================================================

(defn transform-role
  "Transform role to Gemini format"
  [role]
  (case role
    :user "user"
    :assistant "model"
    :system "user" ; Gemini uses "user" for system messages
    (name role)))

(defn transform-messages
  "Transform messages to Gemini format"
  [messages]
  (let [grouped-messages (reduce
                          (fn [acc msg]
                            (let [role (transform-role (:role msg))]
                              (if (and (seq acc) (= role (:role (last acc))))
                                (update-in acc [(dec (count acc)) :parts]
                                           conj {:text (:content msg)})
                                (conj acc {:role role
                                           :parts [{:text (:content msg)}]}))))
                          []
                          messages)]
    (vec grouped-messages)))

(defn transform-tools
  "Transform tools to Gemini format"
  [tools]
  (when tools
    {:function_declarations
     (map (fn [tool]
            (let [func (:function tool)]
              {:name (:name func)
               :description (:description func)
               :parameters (:parameters func)}))
          tools)}))

(defn transform-tool-choice
  "Transform tool choice to Gemini format"
  [tool-choice]
  (cond
    (= tool-choice :auto) "AUTO"
    (= tool-choice :none) "NONE"
    (= tool-choice :any) "ANY"
    (map? tool-choice) {:mode "ANY" :allowed_function_names [(:name tool-choice)]}
    :else "AUTO"))

(defn extract-system-instruction
  "Extract system instruction from messages"
  [messages]
  (when-let [system-msg (first (filter #(= :system (:role %)) messages))]
    {:parts [{:text (:content system-msg)}]}))

(defn transform-generation-config
  "Transform generation config to Gemini format"
  [request]
  (let [config {}]
    (cond-> config
      (:temperature request) (assoc :temperature (:temperature request))
      (:top-p request) (assoc :topP (:top-p request))
      (:max-tokens request) (assoc :maxOutputTokens (:max-tokens request))
      (:stop request) (assoc :stopSequences (if (string? (:stop request))
                                              [(:stop request)]
                                              (:stop request))))))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-tool-calls
  "Transform Gemini function calls to standard format"
  [function-calls]
  (when function-calls
    (map (fn [call]

           {:id (str (java.util.UUID/randomUUID))
            :type "function"
            :function {:name (:name call)
                       :arguments (json/encode (:args call))}})
         function-calls)))

(defn transform-candidate
  "Transform Gemini candidate to standard format"
  [candidate]
  (let [content (:content candidate)
        parts (:parts content)
        text-content (str/join (map :text parts))
        tool-calls (when-let [calls (seq (map :functionCall parts))]
                     (transform-tool-calls calls))]
    {:index 0
     :message {:role :assistant
               :content (when (seq text-content) text-content)
               :tool-calls tool-calls}
     :finish-reason (case (:finish_reason candidate)
                      "STOP" :stop
                      "MAX_TOKENS" :length
                      "SAFETY" :content_filter
                      "RECITATION" :content_filter
                      nil)}))

(defn transform-usage
  "Transform Gemini usage to standard format"
  [usage-metadata]
  (when usage-metadata
    {:prompt-tokens (get-in usage-metadata [:prompt_token_count] 0)
     :completion-tokens (get-in usage-metadata [:candidates_token_count] 0)
     :total-tokens (get-in usage-metadata [:total_token_count] 0)}))

(defn transform-response
  "Transform Gemini response to standard format"
  [response]
  (let [body (:body response)
        candidates (:candidates body)
        usage (:usageMetadata body)]
    {:id (get-in body [:candidates 0 :content :parts 0 :text] (str (java.util.UUID/randomUUID)))
     :object "chat.completion"
     :created (quot (System/currentTimeMillis) 1000)
     :model (get-in body [:model_version] "gemini-unknown")
     :choices (map transform-candidate candidates)
     :usage (transform-usage usage)}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle Gemini API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-info (get body :error {})
        message (or (:message error-info) "Unknown error")
        provider-code (:code error-info)
        request-id (get-in response [:headers "x-request-id"])]
    
    (throw (errors/http-status->error 
             status 
             "gemini" 
             message
             :provider-code provider-code
             :request-id request-id
             :body body))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for Gemini models (in USD)"
  {"gemini-1.5-flash" {:input 0.00000075 :output 0.000003}
   "gemini-1.5-flash-latest" {:input 0.00000075 :output 0.000003}
   "gemini-1.5-pro" {:input 0.00000125 :output 0.000005}
   "gemini-1.5-pro-latest" {:input 0.00000125 :output 0.000005}
   "gemini-2.0-flash" {:input 0.00000015 :output 0.0000006}
   "gemini-2.0-flash-latest" {:input 0.00000015 :output 0.0000006}
   "gemini-2.0-pro" {:input 0.00000125 :output 0.000005}
   "gemini-2.0-pro-latest" {:input 0.00000125 :output 0.000005}
   "gemini-2.5-flash-lite" {:input 0.00000015 :output 0.0000006}})

(def default-model-mapping
  "Default model name mappings"
  {})

;; ============================================================================
;; Gemini Provider Implementation Functions
;; ============================================================================

(defn transform-request-impl
  "Gemini-specific transform-request implementation"
  [provider-name request config]
  (let [model (:model request)
        ;; Map model name to -latest version if needed
        mapped-model (get default-model-mapping model model)
        system-instruction (extract-system-instruction (:messages request))
        filtered-messages (filter #(not= :system (:role %)) (:messages request))
        transformed {:model mapped-model
                    :contents (transform-messages filtered-messages)
                    :generation_config (transform-generation-config request)}]
    
    ;; Add system instruction if present
    (cond-> transformed
      system-instruction (assoc :system_instruction system-instruction)
      (:tools request) (assoc :tools [(transform-tools (:tools request))])
      (:tool-choice request) (assoc :tool_config {:function_calling_config {:mode (transform-tool-choice (:tool-choice request))}}))))

(defn make-request-impl
  "Gemini-specific make-request implementation"
  [provider-name transformed-request thread-pool telemetry config]
  (let [model (:model transformed-request)
        url (str (:api-base config "https://generativelanguage.googleapis.com/v1beta") "/models/" model ":generateContent")
        ;; Remove :model from the request body - Gemini only uses it in the URL
        request-body (dissoc transformed-request :model)]
    (errors/wrap-http-errors
      "gemini"
      #(let [start-time (System/currentTimeMillis)
             response (http/post url
                                 (conj {:headers {"x-goog-api-key" (:api-key config)
                                                  "Content-Type" "application/json"
                                                  "User-Agent" "litellm-clj/1.0.0"}
                                        :body (json/encode request-body)
                                        :timeout (:timeout config 30000)
                                        :async? true
                                        :as :json}
                                       (when thread-pool
                                         {:executor thread-pool})))
             duration (- (System/currentTimeMillis) start-time)]
         ;; Handle errors if response has error status
         (when (>= (:status @response) 400)
           (handle-error-response :gemini @response))
         
         response))))

(defn transform-response-impl
  "Gemini-specific transform-response implementation"
  [provider-name response]
  (transform-response response))

(defn supports-streaming-impl
  "Gemini-specific supports-streaming? implementation"
  [provider-name]
  true)

(defn supports-function-calling-impl
  "Gemini-specific supports-function-calling? implementation"
  [provider-name]
  true)

(defn get-rate-limits-impl
  "Gemini-specific get-rate-limits implementation"
  [provider-name]
  {:requests-per-minute 60
   :tokens-per-minute 60000})

(defn health-check-impl
  "Gemini-specific health-check implementation"
  [provider-name thread-pool config]
  (try
    (let [response (http/post (str (:api-base config "https://generativelanguage.googleapis.com/v1beta") "/models/gemini-2.5-flash-lite:generateContent")
                              (conj {:headers {"x-goog-api-key" (:api-key config)
                                               "Content-Type" "application/json"
                                               "User-Agent" "litellm-clj/1.0.0"}
                                     :body (json/encode {:contents [{:role "user" :parts [{:text "hi"}]}]
                                                        :generation_config {:maxOutputTokens 1}})
                                     :timeout 5000
                                     :as :json}
                                    (when thread-pool
                                      {:executor thread-pool})))]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "Gemini health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "Gemini-specific get-cost-per-token implementation"
  [provider-name model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defn transform-streaming-chunk
  "Transform Gemini streaming chunk to standard format"
  [chunk]
  (let [candidates (:candidates chunk)
        candidate (first candidates)
        content (:content candidate)
        parts (:parts content)
        text-content (str/join (map :text parts))
        function-calls (when-let [calls (seq (mapcat :function_call parts))]
                         (transform-tool-calls calls))]
    {:id (str (java.util.UUID/randomUUID))
     :object "chat.completion.chunk"
     :created (quot (System/currentTimeMillis) 1000)
     :model (get-in chunk [:model_version] "gemini-unknown")
     :choices [{:index 0
               :delta {:role :assistant
                      :content (when (seq text-content) text-content)
                      :tool-calls function-calls}
               :finish-reason (case (:finish_reason candidate)
                               "STOP" :stop
                               "MAX_TOKENS" :length
                               "SAFETY" :content_filter
                               "RECITATION" :content_filter
                               nil)}]}))

(defn transform-streaming-chunk-impl
  "Gemini-specific transform-streaming-chunk implementation for multimethod"
  [provider-name chunk]
  (transform-streaming-chunk chunk))

(defn make-streaming-request-impl
  "Gemini-specific make-streaming-request implementation"
  [provider-name transformed-request thread-pool config]
  (let [model (:model transformed-request)
        url (str (:api-base config "https://generativelanguage.googleapis.com/v1beta") "/models/" model ":streamGenerateContent")
        request-body (dissoc transformed-request :model)
        output-ch (streaming/create-stream-channel)]
    (go
      (try
        (let [response (http/post url
                                  {:headers {"x-goog-api-key" (:api-key config)
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode request-body)
                                   :timeout (:timeout config 30000)
                                   :as :stream})]
          
          ;; Handle errors
          (when (>= (:status response) 400)
            (>! output-ch (streaming/stream-error "gemini" 
                                                  (str "HTTP " (:status response))
                                                  :status (:status response)))
            (streaming/close-stream! output-ch))
          
          ;; Process streaming response - Gemini uses JSON-per-line, not SSE
          (when (= 200 (:status response))
            (let [body (:body response)
                  reader (java.io.BufferedReader. 
                          (java.io.InputStreamReader. body "UTF-8"))]
              (loop []
                (when-let [line (.readLine reader)]
                  (when (seq (str/trim line))
                    (try
                      (let [parsed (json/decode line true)
                            transformed (transform-streaming-chunk-impl :gemini parsed)]
                        (>! output-ch transformed))
                      (catch Exception e
                        (log/debug "Failed to parse Gemini streaming chunk" {:line line :error (.getMessage e)}))))
                  (recur)))
              (.close reader)
              (streaming/close-stream! output-ch))))
        
        (catch Exception e
          (log/error "Error in streaming request" {:error (.getMessage e)})
          (>! output-ch (streaming/stream-error "gemini" (.getMessage e)))
          (streaming/close-stream! output-ch))))
    
    output-ch))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available Gemini models"
  [provider]
  (try
    (let [response (http/get (str (:api-base provider) "/models")
                            {:headers {"x-goog-api-key" (:api-key provider)}
                             :as :json})]
      (if (= 200 (:status response))
        (->> (get-in response [:body :models])
             (filter #(str/starts-with? (:name %) "models/gemini"))
             (map #(str/replace (:name %) #"^models/" "")))
        (throw (ex-info "Failed to list models" {:status (:status response)}))))
    (catch Exception e
      (log/error "Error listing Gemini models" e)
      [])))

(defn validate-api-key
  "Validate Gemini API key"
  [api-key]
  (try
    (let [response (http/post "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash-latest:generateContent"
                            {:headers {"x-goog-api-key" api-key
                                       "Content-Type" "application/json"}
                             :body (json/encode {:contents [{:role "user" :parts [{:text "hi"}]}]
                                                :generation_config {:maxOutputTokens 1}})
                             :timeout 5000})]
      (= 200 (:status response)))
    (catch Exception e
      (log/debug "API key validation failed" {:error (.getMessage e)})
      false)))

;; ============================================================================
;; Provider Testing
;; ============================================================================

(defn test-gemini-connection
  "Test Gemini connection with a simple request"
  [provider thread-pool telemetry]
  (let [test-request {:model "gemini-1.5-flash-latest"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (transform-request-impl :gemini test-request provider)
            response-future (make-request-impl :gemini transformed thread-pool telemetry provider)
            response @response-future
            standard-response (transform-response-impl :gemini response)]
        {:success true
         :provider "gemini"
         :model "gemini-1.5-flash-latest"
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "gemini"
         :error (.getMessage e)
         :error-type (type e)}))))
