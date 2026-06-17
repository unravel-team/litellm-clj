(ns litellm.providers.openai-compatible
  "Shared helpers for providers that expose an OpenAI-compatible chat API."
  (:require [cheshire.core :as json]
            [clojure.core.async :refer [go >!]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as http]
            [litellm.errors :as errors]
            [litellm.streaming :as streaming]))

(def protected-extra-body-keys
  "Request/config keys that :extra-body is not allowed to override."
  #{:model
    :messages
    :stream
    :tools
    :tool_choice
    :tool-choice
    :functions
    :function_call
    :function-call
    :api-key
    :api_key
    :api-base
    :api_base
    :authorization
    :headers
    :auth
    :config})

(defn- provider-name->string [provider-name]
  (if (keyword? provider-name)
    (name provider-name)
    (str provider-name)))

(defn kw->api-string
  "Convert Clojure keyword/string names to provider snake_case strings."
  [v]
  (when (some? v)
    (str/replace (if (keyword? v) (name v) (str v)) "-" "_")))

(defn- api-keyword [k]
  (if (keyword? k)
    (keyword (kw->api-string k))
    k))

(defn- maybe-keyword [v]
  (cond
    (nil? v) nil
    (keyword? v) v
    (string? v) (keyword v)
    :else (keyword (str v))))

(defn transform-api-value
  "Recursively convert keyword values and kebab-case map keys to API strings.

  Useful for small provider option maps like response_format, thinking,
  stream_options. Avoid using this on user JSON Schema parameter maps if exact
  string keys matter."
  [v]
  (cond
    (keyword? v) (kw->api-string v)
    (map? v) (into {}
                   (map (fn [[k value]]
                          [(api-keyword k) (transform-api-value value)]))
                   v)
    (vector? v) (mapv transform-api-value v)
    (sequential? v) (map transform-api-value v)
    :else v))

(defn transform-messages
  "Transform standard messages to OpenAI-compatible wire format."
  [messages]
  (mapv (fn [msg]
          (let [base {:role (kw->api-string (:role msg))
                      :content (:content msg)}]
            (cond-> base
              (:name msg) (assoc :name (:name msg))
              (contains? msg :tool-call-id) (assoc :tool_call_id (:tool-call-id msg))
              (contains? msg :tool-calls) (assoc :tool_calls (:tool-calls msg))
              (contains? msg :reasoning-content) (assoc :reasoning_content (:reasoning-content msg))
              (contains? msg :partial) (assoc :partial (:partial msg)))))
        messages))

(defn- normalize-tool-type [tool]
  (kw->api-string (or (:type tool) (:tool-type tool) "function")))

(defn- normalize-tool-function [function]
  (let [description (or (:description function) (:function-description function))
        parameters (or (:parameters function) (:function-parameters function))]
    (cond-> {:name (or (:name function) (:function-name function))}
      description (assoc :description description)
      parameters (assoc :parameters parameters)
      (contains? function :strict) (assoc :strict (:strict function))
      (contains? function :strict?) (assoc :strict (:strict? function)))))

(defn transform-tools
  "Normalize canonical and legacy tool definitions to OpenAI-compatible shape."
  [tools]
  (when tools
    (mapv (fn [tool]
            {:type (normalize-tool-type tool)
             :function (normalize-tool-function (:function tool))})
          tools)))

(defn transform-tool-choice
  "Transform tool_choice values while preserving provider-specific maps."
  [tool-choice]
  (cond
    (keyword? tool-choice) (kw->api-string tool-choice)
    (map? tool-choice) (transform-api-value tool-choice)
    :else tool-choice))

(defn merge-extra-body
  "Merge known request fields and :extra-body while blocking protected overrides."
  [base optional-fields extra-body]
  (let [extra-body (or extra-body {})
        protected (seq (filter protected-extra-body-keys (keys extra-body)))]
    (when protected
      (throw (errors/invalid-request
              "extra-body cannot override protected request keys"
              :request extra-body
              :errors {:protected-keys (vec protected)})))
    (merge base optional-fields extra-body)))

(defn transform-function-call
  "Transform legacy function_call response shape."
  [function-call]
  (when function-call
    {:name (:name function-call)
     :arguments (:arguments function-call)}))

(defn transform-tool-calls
  "Transform OpenAI-compatible tool calls to standard response shape."
  [tool-calls]
  (when tool-calls
    (mapv (fn [tool-call]
            (cond-> {:id (:id tool-call)
                     :type (:type tool-call)}
              (contains? tool-call :index) (assoc :index (:index tool-call))
              (:function tool-call) (assoc :function (transform-function-call (:function tool-call)))))
          tool-calls)))

(defn- assoc-message-extras [base message]
  (cond-> base
    (:tool_calls message) (assoc :tool-calls (transform-tool-calls (:tool_calls message)))
    (:function_call message) (assoc :function-call (transform-function-call (:function_call message)))
    (contains? message :reasoning_content) (assoc :reasoning-content (:reasoning_content message))))

(defn transform-message
  "Transform an OpenAI-compatible message to standard response shape."
  [message]
  (assoc-message-extras {:role (maybe-keyword (:role message))
                         :content (:content message)}
                        message))

(defn transform-choice
  "Transform an OpenAI-compatible choice to standard response shape."
  [choice]
  {:index (:index choice)
   :message (transform-message (:message choice))
   :finish-reason (maybe-keyword (:finish_reason choice))})

(defn transform-usage
  "Transform OpenAI-compatible usage, preserving cache detail fields."
  [usage]
  (when usage
    (let [cached-tokens (or (:cached_tokens usage)
                            (get-in usage [:prompt_tokens_details :cached_tokens]))]
      (cond-> {:prompt-tokens (:prompt_tokens usage)
               :completion-tokens (:completion_tokens usage)
               :total-tokens (:total_tokens usage)}
        (some? cached-tokens) (assoc :cached-tokens cached-tokens)
        (:prompt_tokens_details usage) (assoc :prompt-tokens-details (:prompt_tokens_details usage))
        (:completion_tokens_details usage) (assoc :completion-tokens-details (:completion_tokens_details usage))))))

(defn- response-body [response]
  (if (and (map? response) (contains? response :body))
    (:body response)
    response))

(defn- response-metadata [body]
  {:id (:id body)
   :object (:object body)
   :created (:created body)
   :model (:model body)})

(defn transform-response
  "Transform a standard OpenAI-compatible chat response to litellm-clj shape."
  [response]
  (let [body (response-body response)]
    (assoc (response-metadata body)
           :choices (mapv transform-choice (:choices body))
           :usage (transform-usage (:usage body)))))

(defn transform-delta
  "Transform an OpenAI-compatible streaming delta to standard response shape."
  [delta]
  (assoc-message-extras {:role (maybe-keyword (:role delta))
                         :content (:content delta)}
                        delta))

(defn transform-streaming-chunk
  "Transform an OpenAI-compatible streaming chunk to litellm-clj shape."
  [chunk]
  (let [body (response-body chunk)]
    (cond-> (assoc (response-metadata body)
                   :choices (mapv (fn [choice]
                                     {:index (:index choice)
                                      :delta (transform-delta (:delta choice))
                                      :finish-reason (maybe-keyword (:finish_reason choice))})
                                   (:choices body)))
      (:usage body) (assoc :usage (transform-usage (:usage body))))))

(defn default-headers
  "Build standard JSON headers for an OpenAI-compatible request."
  [config]
  (merge {"Authorization" (str "Bearer " (:api-key config))
          "Content-Type" "application/json"
          "User-Agent" "litellm-clj/1.0.0"}
         (:headers config)))

(defn handle-error-response
  "Throw a litellm error from an OpenAI-compatible HTTP error response."
  [provider-name response]
  (let [provider (provider-name->string provider-name)
        status (:status response)
        body (:body response)
        error-info (if (map? body) (get body :error {}) {})
        message (or (:message error-info) (str "HTTP " status))
        provider-code (or (:code error-info) (:type error-info))
        request-id (or (get-in response [:headers "x-request-id"])
                       (get-in response [:headers "X-Request-Id"]))
        retry-after (some-> (get-in response [:headers "retry-after"]) Long/parseLong)]
    (throw (errors/http-status->error
            status
            provider
            message
            :provider-code provider-code
            :retry-after retry-after
            :request-id request-id
            :body body))))

(defn post-json-async
  "POST a JSON chat request and return Hato's async response future."
  [provider-name url transformed-request thread-pool config]
  (let [provider (provider-name->string provider-name)]
    (errors/wrap-http-errors
     provider
     #(let [response (http/post url
                                (cond-> {:headers (default-headers config)
                                          :body (json/encode transformed-request)
                                          :timeout (:timeout config 30000)
                                          :async? true
                                          :as :json}
                                  thread-pool (assoc :executor thread-pool)))]
        (when (>= (:status @response) 400)
          (handle-error-response provider-name @response))
        response))))

(defn post-sse
  "POST a JSON request and stream SSE chunks through a core.async channel."
  ([provider-name url transformed-request thread-pool config]
   (post-sse provider-name url transformed-request thread-pool config transform-streaming-chunk))
  ([provider-name url transformed-request thread-pool config transform-chunk]
   (let [provider (provider-name->string provider-name)
         output-ch (streaming/create-stream-channel)]
     (go
       (try
         (let [response (http/post url
                                   (cond-> {:headers (default-headers config)
                                             :body (json/encode transformed-request)
                                             :timeout (:timeout config 30000)
                                             :as :stream}
                                     thread-pool (assoc :executor thread-pool)))]
           (if (= 200 (:status response))
             (let [body (:body response)
                   reader (java.io.BufferedReader.
                           (java.io.InputStreamReader. ^java.io.InputStream body "UTF-8"))]
               (try
                 (loop []
                   (when-let [line (streaming/read-sse-line! reader)]
                     (when-let [parsed (streaming/parse-sse-line line json/decode)]
                       (>! output-ch (transform-chunk parsed)))
                     (recur)))
                 (finally
                   (.close ^java.io.BufferedReader reader)
                   (streaming/close-stream! output-ch))))
             (do
               (>! output-ch (streaming/stream-error provider
                                                     (str "HTTP " (:status response))
                                                     :status (:status response)))
               (streaming/close-stream! output-ch))))
         (catch Exception e
           (log/error "Error in OpenAI-compatible streaming request" {:provider provider
                                                                       :error (.getMessage e)})
           (>! output-ch (streaming/stream-error provider (.getMessage e)))
           (streaming/close-stream! output-ch))))
     output-ch)))
