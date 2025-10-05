(ns litellm.providers.gemini
  "Google Gemini provider implementation for LiteLLM"
  (:require [litellm.providers.core :as core]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [com.climate.claypoole :as cp]))

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
            {:name (:function-name (:function tool))
             :description (:function-description (:function tool))
             :parameters (:function-parameters (:function tool))})
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
        function-calls (when-let [calls (seq (mapcat :function_call parts))]
                         (transform-tool-calls calls))]
    {:index 0
     :message {:role :assistant
               :content (when (seq text-content) text-content)
               :tool-calls function-calls}
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
        usage (:usage_metadata body)]
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
        error-info (get body :error {})]
    
    (case status
      400 (throw (ex-info (or (:message error-info) "Bad request")
                          {:type :bad-request-error
                           :provider "gemini"
                           :details error-info}))
      401 (throw (ex-info "Authentication failed"
                          {:type :authentication-error
                           :provider "gemini"}))
      403 (throw (ex-info "Permission denied"
                          {:type :permission-error
                           :provider "gemini"}))
      404 (throw (ex-info "Model not found"
                          {:type :model-not-found-error
                           :provider "gemini"
                           :model (get-in body [:error :details :model])}))
      429 (throw (ex-info "Rate limit exceeded"
                          {:type :rate-limit-error
                           :provider "gemini"
                           :retry-after (get-in response [:headers "retry-after"])}))
      500 (throw (ex-info "Internal server error"
                          {:type :server-error
                           :provider "gemini"}))
      (throw (ex-info (or (:message error-info) "Unknown error")
                      {:type :provider-error
                       :provider "gemini"
                       :status status
                       :code (:code error-info)
                       :data error-info})))))

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
   "gemini-2.0-pro-latest" {:input 0.00000125 :output 0.000005}})

(def default-model-mapping
  "Default model name mappings"
  {"gemini-1.5-flash" "gemini-1.5-flash-latest"
   "gemini-1.5-pro" "gemini-1.5-pro-latest"
   "gemini-2.0-flash" "gemini-2.0-flash-latest"
   "gemini-2.0-pro" "gemini-2.0-pro-latest"})

;; ============================================================================
;; Gemini Provider Record
;; ============================================================================

(defrecord GeminiProvider [api-key api-base model-mapping rate-limits cost-map timeout]
  core/LLMProvider
  
  (provider-name [_] "gemini")
  
  (transform-request [provider request]
    (let [model (:model request)
          system-instruction (extract-system-instruction (:messages request))
          filtered-messages (filter #(not= :system (:role %)) (:messages request))
          transformed {:contents (transform-messages filtered-messages)
                      :generation_config (transform-generation-config request)}]
      
      ;; Add system instruction if present
      (cond-> transformed
        system-instruction (assoc :system_instruction system-instruction)
        (:tools request) (assoc :tools [(transform-tools (:tools request))])
        (:tool-choice request) (assoc :tool_config {:function_calling_config {:mode (transform-tool-choice (:tool-choice request))}}))))
  
  (make-request [provider transformed-request thread-pools telemetry]
    (let [url (str (:api-base provider) "/models/" (:model transformed-request) ":generateContent")]
      (cp/future (:api-calls thread-pools)
        (let [start-time (System/currentTimeMillis)
              response (http/post url
                                  {:headers {"x-goog-api-key" (:api-key provider)
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode transformed-request)
                                   :timeout (:timeout provider 30000)
                                   :as :json})
              duration (- (System/currentTimeMillis) start-time)]
          
          ;; Handle errors
          (when (>= (:status response) 400)
            (handle-error-response provider response))
          
          response))))
  
  (transform-response [provider response]
    (transform-response response))
  
  (supports-streaming? [_] true)
  
  (supports-function-calling? [_] true)
  
  (get-rate-limits [provider]
    (:rate-limits provider {:requests-per-minute 60
                           :tokens-per-minute 60000}))
  
  (health-check [provider thread-pools]
    (cp/future (:health-checks thread-pools)
      (try
        (let [response (http/post (str (:api-base provider) "/models/gemini-1.5-flash-latest:generateContent")
                                  {:headers {"x-goog-api-key" (:api-key provider)
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode {:contents [{:role "user" :parts [{:text "hi"}]}]
                                                      :generation_config {:maxOutputTokens 1}})
                                   :timeout 5000
                                   :as :json})]
          (= 200 (:status response)))
        (catch Exception e
          (log/warn "Gemini health check failed" {:error (.getMessage e)})
          false))))
  
  (get-cost-per-token [provider model]
    (get (:cost-map provider) model {:input 0.0 :output 0.0})))

;; ============================================================================
;; Provider Factory
;; ============================================================================

(defn create-gemini-provider
  "Create Gemini provider instance"
  [config]
  (map->GeminiProvider
    {:api-key (:api-key config)
     :api-base (:api-base config "https://generativelanguage.googleapis.com/v1beta")
     :model-mapping (merge default-model-mapping (:model-mapping config {}))
     :rate-limits (:rate-limits config {:requests-per-minute 60
                                       :tokens-per-minute 60000})
     :cost-map (merge default-cost-map (:cost-map config {}))
     :timeout (:timeout config 30000)}))

;; ============================================================================
;; Provider Registration
;; ============================================================================

(defn register-gemini-provider!
  "Register Gemini provider in the global registry"
  []
  (core/register-provider! "gemini" create-gemini-provider))

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

(defn handle-streaming-response
  "Handle streaming response from Gemini"
  [response callback]
  (let [body (:body response)]
    (doseq [line (str/split-lines body)]
      (when (str/starts-with? line "data: ")
        (let [data (subs line 6)]
          (when-not (= data "[DONE]")
            (try
              (let [parsed (json/decode data true)
                    transformed (transform-streaming-chunk parsed)]
                (callback transformed))
              (catch Exception e
                (log/debug "Failed to parse streaming chunk" {:line line :error (.getMessage e)}))))))))

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
  [provider thread-pools telemetry]
  (let [test-request {:model "gemini-1.5-flash-latest"
                     :messages [{:role :user :content "Hello"}]
                     :max-tokens 5}]
    (try
      (let [transformed (core/transform-request provider test-request)
            response-future (core/make-request provider transformed thread-pools telemetry)
            response @response-future
            standard-response (core/transform-response provider response)]
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

;; Auto-register the provider when namespace is loaded
(register-gemini-provider!)
