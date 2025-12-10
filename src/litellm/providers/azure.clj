(ns litellm.providers.azure
  "Azure OpenAI provider implementation for LiteLLM"
  (:require [litellm.streaming :as streaming]
            [litellm.errors :as errors]
            [hato.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go >!]]))

;; ============================================================================
;; Message Transformations (same as OpenAI)
;; ============================================================================

(defn transform-messages
  "Transform messages to Azure OpenAI format (OpenAI-compatible)"
  [messages]
  (map (fn [msg]
         (let [base {:role (name (:role msg))
                     :content (:content msg)}]
           (cond-> base
             (:name msg) (assoc :name (:name msg))
             (:tool-call-id msg) (assoc :tool_call_id (:tool-call-id msg)))))
       messages))

(defn transform-tools
  "Transform tools to Azure OpenAI format (OpenAI-compatible)"
  [tools]
  (when tools
    (map (fn [tool]
           {:type (:tool-type tool "function")
            :function (select-keys (:function tool) [:name :description :parameters])})
         tools)))

(defn transform-tool-choice
  "Transform tool choice to Azure OpenAI format"
  [tool-choice]
  (cond
    (keyword? tool-choice) (name tool-choice)
    (map? tool-choice) tool-choice
    :else tool-choice))

;; ============================================================================
;; Response Transformations (same as OpenAI)
;; ============================================================================

(defn transform-tool-calls
  "Transform Azure OpenAI tool calls to standard format"
  [tool-calls]
  (when tool-calls
    (map (fn [tool-call]
           {:id (:id tool-call)
            :type (:type tool-call)
            :function {:name (get-in tool-call [:function :name])
                       :arguments (get-in tool-call [:function :arguments])}})
         tool-calls)))

(defn transform-message
  "Transform Azure OpenAI message to standard format"
  [message]
  (cond-> {:role (keyword (:role message))
           :content (:content message)}
    (:tool_calls message) (assoc :tool-calls (transform-tool-calls (:tool_calls message)))))

(defn transform-choice
  "Transform Azure OpenAI choice to standard format"
  [choice]
  {:index (:index choice)
   :message (transform-message (:message choice))
   :finish-reason (keyword (:finish_reason choice))})

(defn transform-usage
  "Transform Azure OpenAI usage to standard format"
  [usage]
  (when usage
    {:prompt-tokens (:prompt_tokens usage)
     :completion-tokens (:completion_tokens usage)
     :total-tokens (:total_tokens usage)}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle Azure OpenAI API error responses"
  [provider response]
  (let [status (:status response)
        body (:body response)
        error-info (get body :error {})
        message (or (:message error-info) "Unknown error")
        provider-code (:code error-info)
        request-id (get-in response [:headers "x-request-id"])]
    (throw (errors/http-status->error
            status
            "azure"
            message
            :provider-code provider-code
            :request-id request-id
            :body body))))

;; ============================================================================
;; URL Building
;; ============================================================================

(defn build-chat-url
  "Build the Azure OpenAI chat completions URL.

  Config should contain:
  - :api-base - Azure resource endpoint (e.g., https://my-resource.openai.azure.com)
  - :deployment - Deployment name (e.g., gpt-4-deployment)
  - :api-version - API version (default: 2024-10-21)"
  [config]
  (let [api-base (:api-base config)
        deployment (:deployment config)
        api-version (:api-version config "2024-10-21")]
    (str api-base "/openai/deployments/" deployment "/chat/completions?api-version=" api-version)))

(defn build-embedding-url
  "Build the Azure OpenAI embeddings URL."
  [config]
  (let [api-base (:api-base config)
        deployment (:deployment config)
        api-version (:api-version config "2024-10-21")]
    (str api-base "/openai/deployments/" deployment "/embeddings?api-version=" api-version)))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for Azure OpenAI models (in USD)
   Note: Azure pricing may vary by region and agreement"
  {"gpt-4" {:input 0.00003 :output 0.00006}
   "gpt-4-turbo" {:input 0.00001 :output 0.00003}
   "gpt-4o" {:input 0.000005 :output 0.000015}
   "gpt-4o-mini" {:input 0.00000015 :output 0.0000006}
   "gpt-35-turbo" {:input 0.0000005 :output 0.0000015}
   "gpt-35-turbo-16k" {:input 0.000003 :output 0.000004}})

;; ============================================================================
;; Azure OpenAI Provider Implementation Functions
;; ============================================================================

(defn transform-request-impl
  "Azure OpenAI-specific transform-request implementation.
  Note: model is not included in body as it's determined by deployment."
  [provider-name request config]
  (let [base {:messages (transform-messages (:messages request))}]

    ;; Add optional parameters only if they are not nil
    (cond-> base
      (:max-tokens request) (assoc :max_tokens (:max-tokens request))
      (:temperature request) (assoc :temperature (:temperature request))
      (:top-p request) (assoc :top_p (:top-p request))
      (:frequency-penalty request) (assoc :frequency_penalty (:frequency-penalty request))
      (:presence-penalty request) (assoc :presence_penalty (:presence-penalty request))
      (:stop request) (assoc :stop (:stop request))
      (contains? request :stream) (assoc :stream (:stream request))
      (:tools request) (assoc :tools (transform-tools (:tools request)))
      (:tool-choice request) (assoc :tool_choice (transform-tool-choice (:tool-choice request))))))

(defn make-request-impl
  "Azure OpenAI-specific make-request implementation"
  [provider-name transformed-request thread-pool telemetry config]
  (let [url (build-chat-url config)]
    (errors/wrap-http-errors
     "azure"
     #(let [start-time (System/currentTimeMillis)
            response (http/post url
                                (conj {:headers {"api-key" (:api-key config)
                                                 "Content-Type" "application/json"
                                                 "User-Agent" "litellm-clj/1.0.0"}
                                       :body (json/encode transformed-request)
                                       :timeout (:timeout config 30000)
                                       :async? true
                                       :as :json}
                                      (when thread-pool
                                        {:executor thread-pool})))
            duration (- (System/currentTimeMillis) start-time)]

         ;; Handle errors if response has error status
        (when (>= (:status @response) 400)
          (handle-error-response :azure @response))

        response))))

(defn transform-response-impl
  "Azure OpenAI-specific transform-response implementation"
  [provider-name response]
  (let [body (:body response)]
    {:id (:id body)
     :object (:object body)
     :created (:created body)
     :model (:model body)
     :choices (map transform-choice (:choices body))
     :usage (transform-usage (:usage body))}))

(defn supports-streaming-impl
  "Azure OpenAI-specific supports-streaming? implementation"
  [provider-name]
  true)

(defn supports-function-calling-impl
  "Azure OpenAI-specific supports-function-calling? implementation"
  [provider-name]
  true)

(defn get-rate-limits-impl
  "Azure OpenAI-specific get-rate-limits implementation"
  [provider-name]
  {:requests-per-minute 1000
   :tokens-per-minute 120000})

(defn health-check-impl
  "Azure OpenAI-specific health-check implementation"
  [provider-name thread-pool config]
  (try
    ;; Azure doesn't have a simple /models endpoint like OpenAI
    ;; We'll just try a minimal request to check connectivity
    (let [url (build-chat-url config)
          response (http/post url
                              (conj {:headers {"api-key" (:api-key config)
                                               "Content-Type" "application/json"}
                                     :body (json/encode {:messages [{:role "user" :content "test"}]
                                                         :max_tokens 1})
                                     :timeout 5000
                                     :as :json}
                                    (when thread-pool
                                      {:executor thread-pool})))]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "Azure OpenAI health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "Azure OpenAI-specific get-cost-per-token implementation"
  [provider-name model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defn transform-streaming-chunk-impl
  "Azure OpenAI-specific transform-streaming-chunk implementation"
  [provider-name chunk]
  (let [choice (first (:choices chunk))
        delta (:delta choice)]
    {:id (:id chunk)
     :object (:object chunk)
     :created (:created chunk)
     :model (:model chunk)
     :choices [{:index (:index choice)
                :delta {:role (keyword (:role delta))
                        :content (:content delta)}
                :finish-reason (when (:finish_reason choice)
                                 (keyword (:finish_reason choice)))}]}))

(defn make-streaming-request-impl
  "Azure OpenAI-specific make-streaming-request implementation"
  [provider-name transformed-request thread-pool config]
  (let [url (build-chat-url config)
        output-ch (streaming/create-stream-channel)]
    (go
      (try
        (let [response (http/post url
                                  {:headers {"api-key" (:api-key config)
                                             "Content-Type" "application/json"
                                             "User-Agent" "litellm-clj/1.0.0"}
                                   :body (json/encode transformed-request)
                                   :timeout (:timeout config 30000)
                                   :as :stream})]

          ;; Handle errors
          (when (>= (:status response) 400)
            (>! output-ch (streaming/stream-error "azure"
                                                  (str "HTTP " (:status response))
                                                  :status (:status response)))
            (streaming/close-stream! output-ch))

          ;; Process streaming response
          (when (= 200 (:status response))
            (let [body (:body response)
                  reader (java.io.BufferedReader.
                          (java.io.InputStreamReader. body "UTF-8"))]
              (loop []
                (when-let [line (.readLine reader)]
                  (when-let [parsed (streaming/parse-sse-line line json/decode)]
                    (let [transformed (transform-streaming-chunk-impl :azure parsed)]
                      (>! output-ch transformed)))
                  (recur)))
              (.close reader)
              (streaming/close-stream! output-ch))))

        (catch Exception e
          (log/error "Error in streaming request" {:error (.getMessage e)})
          (>! output-ch (streaming/stream-error "azure" (.getMessage e)))
          (streaming/close-stream! output-ch))))

    output-ch))

;; ============================================================================
;; Embedding Support
;; ============================================================================

(def default-embedding-cost-map
  "Cost per token for Azure OpenAI embedding models (in USD)"
  {"text-embedding-ada-002" {:input 0.0000001 :output 0.0}
   "text-embedding-3-small" {:input 0.00000002 :output 0.0}
   "text-embedding-3-large" {:input 0.00000013 :output 0.0}})

(defn transform-embedding-request-impl
  "Azure OpenAI-specific transform-embedding-request implementation"
  [provider-name request config]
  (let [input (:input request)
        transformed {:input (if (string? input) [input] input)}]
    (cond-> transformed
      (:encoding-format request) (assoc :encoding_format (name (:encoding-format request)))
      (:dimensions request) (assoc :dimensions (:dimensions request))
      (:user request) (assoc :user (:user request)))))

(defn make-embedding-request-impl
  "Azure OpenAI-specific make-embedding-request implementation"
  [provider-name transformed-request thread-pool telemetry config]
  (let [url (build-embedding-url config)]
    (errors/wrap-http-errors
     "azure"
     #(let [start-time (System/currentTimeMillis)
            response (http/post url
                                (conj {:headers {"api-key" (:api-key config)
                                                 "Content-Type" "application/json"
                                                 "User-Agent" "litellm-clj/1.0.0"}
                                       :body (json/encode transformed-request)
                                       :timeout (:timeout config 30000)
                                       :async? true
                                       :as :json}
                                      (when thread-pool
                                        {:executor thread-pool})))
            duration (- (System/currentTimeMillis) start-time)]

         ;; Handle errors if response has error status
        (when (>= (:status @response) 400)
          (handle-error-response :azure @response))

        response))))

(defn transform-embedding-response-impl
  "Azure OpenAI-specific transform-embedding-response implementation"
  [provider-name response]
  (let [body (:body response)]
    {:object (:object body)
     :data (map (fn [item]
                  {:object (:object item)
                   :embedding (:embedding item)
                   :index (:index item)})
                (:data body))
     :model (:model body)
     :usage (transform-usage (:usage body))}))

(defn supports-embeddings-impl
  "Azure OpenAI-specific supports-embeddings? implementation"
  [provider-name]
  true)

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn validate-config
  "Validate Azure OpenAI configuration"
  [config]
  (when-not (:api-base config)
    (throw (ex-info "Azure OpenAI requires :api-base (e.g., https://my-resource.openai.azure.com)"
                    {:config config})))
  (when-not (:deployment config)
    (throw (ex-info "Azure OpenAI requires :deployment (deployment name)"
                    {:config config})))
  (when-not (:api-key config)
    (throw (ex-info "Azure OpenAI requires :api-key"
                    {:config config})))
  config)

(defn test-azure-connection
  "Test Azure OpenAI connection with a simple request"
  [config thread-pool telemetry]
  (let [validated-config (validate-config config)
        test-request {:messages [{:role :user :content "Hello"}]
                      :max-tokens 5}]
    (try
      (let [transformed (transform-request-impl :azure test-request validated-config)
            response-future (make-request-impl :azure transformed thread-pool telemetry validated-config)
            response @response-future
            standard-response (transform-response-impl :azure response)]
        {:success true
         :provider "azure"
         :deployment (:deployment validated-config)
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "azure"
         :error (.getMessage e)
         :error-type (type e)}))))

