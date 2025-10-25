(ns litellm.errors
  "Comprehensive error handling for LiteLLM-clj
  
  This namespace provides:
  - Namespaced error types for clear categorization
  - Rich error data structures with context
  - Error predicates for pattern matching
  - Retry and recoverability analysis"
  (:require [clojure.string :as str]))

;; ============================================================================
;; Error Detection Predicates
;; ============================================================================

;; Client/Configuration Errors (4xx-style, user fixable)
(def client-errors
  #{:litellm/invalid-request
    :litellm/invalid-config
    :litellm/authentication-error
    :litellm/authorization-error
    :litellm/provider-not-found
    :litellm/model-not-found
    :litellm/unsupported-feature
    :litellm/quota-exceeded})

;; Provider/Network Errors (5xx-style, transient)
(def provider-errors
  #{:litellm/rate-limit
    :litellm/timeout
    :litellm/connection-error
    :litellm/server-error
    :litellm/provider-error})

;; Response Errors
(def response-errors
  #{:litellm/invalid-response
    :litellm/streaming-error
    :litellm/content-filter})

;; System Errors
(def system-errors
  #{:litellm/internal-error
    :litellm/resource-exhausted})

(def all-error-types
  (into #{} (concat client-errors provider-errors response-errors system-errors)))

;; ============================================================================
;; Error Data Structure
;; ============================================================================

(defn- build-error-data
  "Build standardized error data map"
  [error-type message & {:keys [provider http-status provider-code retry-after
                                 recoverable? request-id context]
                          :or {recoverable? false}}]
  (cond-> {:type error-type
           :message message
           :recoverable? recoverable?}
    provider (assoc :provider provider)
    http-status (assoc :http-status http-status)
    provider-code (assoc :provider-code provider-code)
    retry-after (assoc :retry-after retry-after)
    request-id (assoc :request-id request-id)
    context (assoc :context context)))

;; ============================================================================
;; Client/Configuration Error Constructors
;; ============================================================================

(defn invalid-request
  "Request validation failed - missing required fields or invalid types"
  [message & {:keys [request errors] :as opts}]
  (ex-info message
           (build-error-data :litellm/invalid-request message
                           :context {:request request :validation-errors errors})))

(defn invalid-config
  "Configuration validation failed"
  [message & {:keys [config errors] :as opts}]
  (ex-info message
           (build-error-data :litellm/invalid-config message
                           :context {:config config :validation-errors errors})))

(defn authentication-error
  "API key invalid or missing"
  [provider message & {:keys [http-status provider-code] :as opts}]
  (ex-info message
           (build-error-data :litellm/authentication-error message
                           :provider provider
                           :http-status (or http-status 401)
                           :provider-code provider-code
                           :recoverable? false)))

(defn authorization-error
  "API key valid but lacks permissions"
  [provider message & {:keys [http-status provider-code resource] :as opts}]
  (ex-info message
           (build-error-data :litellm/authorization-error message
                           :provider provider
                           :http-status (or http-status 403)
                           :provider-code provider-code
                           :recoverable? false
                           :context {:resource resource})))

(defn provider-not-found
  "Requested provider doesn't exist"
  [provider & {:keys [available-providers] :as opts}]
  (ex-info (str "Provider not found: " provider)
           (build-error-data :litellm/provider-not-found
                           (str "Provider not found: " provider)
                           :provider provider
                           :context {:available-providers available-providers})))

(defn model-not-found
  "Model doesn't exist for provider"
  [provider model & {:keys [http-status provider-code available-models] :as opts}]
  (ex-info (str "Model not found: " model)
           (build-error-data :litellm/model-not-found
                           (str "Model not found: " model)
                           :provider provider
                           :http-status (or http-status 404)
                           :provider-code provider-code
                           :context {:model model
                                    :available-models available-models})))

(defn unsupported-feature
  "Feature not supported by provider"
  [provider feature & {:keys [message] :as opts}]
  (let [msg (or message (str "Feature not supported: " feature))]
    (ex-info msg
             (build-error-data :litellm/unsupported-feature msg
                             :provider provider
                             :context {:feature feature}))))

(defn quota-exceeded
  "Account quota exhausted"
  [provider message & {:keys [http-status provider-code quota-type reset-at] :as opts}]
  (ex-info message
           (build-error-data :litellm/quota-exceeded message
                           :provider provider
                           :http-status (or http-status 429)
                           :provider-code provider-code
                           :recoverable? false
                           :context {:quota-type quota-type
                                    :reset-at reset-at})))

;; ============================================================================
;; Provider/Network Error Constructors
;; ============================================================================

(defn rate-limit
  "Rate limit hit - usually recoverable with backoff"
  [provider message & {:keys [http-status provider-code retry-after request-id] :as opts}]
  (ex-info message
           (build-error-data :litellm/rate-limit message
                           :provider provider
                           :http-status (or http-status 429)
                           :provider-code provider-code
                           :retry-after retry-after
                           :recoverable? true
                           :request-id request-id)))

(defn timeout-error
  "Request timeout"
  [provider message & {:keys [timeout-ms request-id] :as opts}]
  (ex-info message
           (build-error-data :litellm/timeout message
                           :provider provider
                           :recoverable? true
                           :request-id request-id
                           :context {:timeout-ms timeout-ms})))

(defn connection-error
  "Network connectivity issues"
  [provider message & {:keys [cause request-id] :as opts}]
  (ex-info message
           (build-error-data :litellm/connection-error message
                           :provider provider
                           :recoverable? true
                           :request-id request-id
                           :context {:cause (when cause (.getMessage cause))})))

(defn server-error
  "Provider's server error (500, 502, 503)"
  [provider message & {:keys [http-status provider-code request-id] :as opts}]
  (ex-info message
           (build-error-data :litellm/server-error message
                           :provider provider
                           :http-status http-status
                           :provider-code provider-code
                           :recoverable? true
                           :request-id request-id)))

(defn provider-error
  "Generic provider-side error"
  [provider message & {:keys [http-status provider-code request-id recoverable?] :as opts}]
  (ex-info message
           (build-error-data :litellm/provider-error message
                           :provider provider
                           :http-status http-status
                           :provider-code provider-code
                           :recoverable? (boolean recoverable?)
                           :request-id request-id)))

;; ============================================================================
;; Response Error Constructors
;; ============================================================================

(defn invalid-response
  "Response doesn't match expected schema"
  [provider message & {:keys [response validation-errors] :as opts}]
  (ex-info message
           (build-error-data :litellm/invalid-response message
                           :provider provider
                           :context {:response response
                                    :validation-errors validation-errors})))

(defn streaming-error
  "Error during streaming operation"
  [provider message & {:keys [recoverable? chunk-number cause] :as opts}]
  (ex-info message
           (build-error-data :litellm/streaming-error message
                           :provider provider
                           :recoverable? (boolean recoverable?)
                           :context {:chunk-number chunk-number
                                    :cause (when cause (.getMessage cause))})))

(defn content-filter
  "Content filtered by provider safety systems"
  [provider message & {:keys [provider-code filter-type] :as opts}]
  (ex-info message
           (build-error-data :litellm/content-filter message
                           :provider provider
                           :provider-code provider-code
                           :recoverable? false
                           :context {:filter-type filter-type})))

;; ============================================================================
;; System Error Constructors
;; ============================================================================

(defn internal-error
  "Unexpected litellm bug"
  [message & {:keys [cause stack-trace] :as opts}]
  (ex-info message
           (build-error-data :litellm/internal-error message
                           :context {:cause (when cause (.getMessage cause))
                                    :stack-trace stack-trace})))

(defn resource-exhausted
  "Thread pool or channel buffer full"
  [message & {:keys [resource-type current-usage limit] :as opts}]
  (ex-info message
           (build-error-data :litellm/resource-exhausted message
                           :recoverable? true
                           :context {:resource-type resource-type
                                    :current-usage current-usage
                                    :limit limit})))

;; ============================================================================
;; Error Predicates
;; ============================================================================

(defn litellm-error?
  "Check if exception is a litellm error"
  [ex]
  (and (instance? clojure.lang.ExceptionInfo ex)
       (contains? all-error-types (:type (ex-data ex)))))

(defn error-type?
  "Check if exception matches a specific error type"
  [ex error-type]
  (and (litellm-error? ex)
       (= error-type (:type (ex-data ex)))))

(defn client-error?
  "Check if error is a client/configuration error"
  [ex]
  (and (litellm-error? ex)
       (contains? client-errors (:type (ex-data ex)))))

(defn provider-error?
  "Check if error is a provider/network error"
  [ex]
  (and (litellm-error? ex)
       (contains? provider-errors (:type (ex-data ex)))))

(defn response-error?
  "Check if error is a response error"
  [ex]
  (and (litellm-error? ex)
       (contains? response-errors (:type (ex-data ex)))))

(defn system-error?
  "Check if error is a system error"
  [ex]
  (and (litellm-error? ex)
       (contains? system-errors (:type (ex-data ex)))))

;; Specific error type predicates
(defn authentication-error?
  [ex]
  (error-type? ex :litellm/authentication-error))

(defn rate-limit-error?
  [ex]
  (error-type? ex :litellm/rate-limit))

(defn timeout-error?
  [ex]
  (error-type? ex :litellm/timeout))

(defn model-not-found-error?
  [ex]
  (error-type? ex :litellm/model-not-found))

(defn streaming-error?
  [ex]
  (error-type? ex :litellm/streaming-error))

;; ============================================================================
;; Error Analysis
;; ============================================================================

(defn recoverable?
  "Check if error is recoverable (retry might succeed)"
  [ex]
  (and (litellm-error? ex)
       (get (ex-data ex) :recoverable? false)))

(defn should-retry?
  "Determine if error should be retried based on type and recoverability"
  [ex & {:keys [max-retries current-retry] :or {max-retries 3 current-retry 0}}]
  (and (< current-retry max-retries)
       (recoverable? ex)
       (or (rate-limit-error? ex)
           (timeout-error? ex)
           (error-type? ex :litellm/server-error)
           (error-type? ex :litellm/connection-error))))

(defn retry-delay
  "Calculate retry delay in milliseconds based on error type and retry count"
  [ex retry-count]
  (let [data (ex-data ex)]
    (cond
      ;; Rate limit with retry-after header
      (and (rate-limit-error? ex) (:retry-after data))
      (* 1000 (:retry-after data))
      
      ;; Rate limit without retry-after - exponential backoff
      (rate-limit-error? ex)
      (* 1000 (Math/pow 2 retry-count))
      
      ;; Server errors - exponential backoff with jitter
      (error-type? ex :litellm/server-error)
      (+ (* 1000 (Math/pow 2 retry-count))
         (rand-int 1000))
      
      ;; Connection/timeout - linear backoff
      (or (timeout-error? ex) (error-type? ex :litellm/connection-error))
      (* 1000 (inc retry-count))
      
      ;; Default
      :else 1000)))

(defn get-error-category
  "Get human-readable error category"
  [ex]
  (when (litellm-error? ex)
    (let [error-type (:type (ex-data ex))]
      (cond
        (contains? client-errors error-type) :client-error
        (contains? provider-errors error-type) :provider-error
        (contains? response-errors error-type) :response-error
        (contains? system-errors error-type) :system-error
        :else :unknown))))

;; ============================================================================
;; Error Formatting
;; ============================================================================

(defn error-summary
  "Create a human-readable error summary"
  [ex]
  (when (litellm-error? ex)
    (let [data (ex-data ex)]
      (str/join " | "
                (filter some?
                        [(:message data)
                         (when (:provider data) (str "Provider: " (:provider data)))
                         (when (:http-status data) (str "HTTP " (:http-status data)))
                         (when (:recoverable? data) "Recoverable")
                         (when (:retry-after data) (str "Retry after " (:retry-after data) "s"))])))))

(defn error-details
  "Get detailed error information as a map"
  [ex]
  (when (litellm-error? ex)
    (let [data (ex-data ex)]
      {:error-type (:type data)
       :category (get-error-category ex)
       :message (:message data)
       :provider (:provider data)
       :http-status (:http-status data)
       :provider-code (:provider-code data)
       :recoverable? (:recoverable? data)
       :retry-after (:retry-after data)
       :request-id (:request-id data)
       :context (:context data)})))

;; ============================================================================
;; Streaming Error Helpers
;; ============================================================================

(defn streaming-error-chunk
  "Create an error chunk for placing on streaming channels"
  [provider message & {:keys [error-type http-status provider-code 
                              recoverable? chunk-number cause]
                       :or {error-type :litellm/streaming-error
                            recoverable? false}}]
  {:type :error
   :error-type error-type
   :provider provider
   :message message
   :http-status http-status
   :provider-code provider-code
   :recoverable? recoverable?
   :chunk-number chunk-number
   :cause (when cause (.getMessage cause))})

(defn error-chunk?
  "Check if a streaming chunk is an error chunk"
  [chunk]
  (= :error (:type chunk)))

(defn chunk->exception
  "Convert an error chunk to an exception"
  [chunk]
  (when (error-chunk? chunk)
    (ex-info (:message chunk)
             (dissoc chunk :type :message))))

;; ============================================================================
;; HTTP Error Wrapping
;; ============================================================================

(defn wrap-http-errors
  "Wrap HTTP calls to convert hato exceptions to litellm errors.
  Preserves HTTP response details when available."
  [provider-name f]
  (try
    (f)
    (catch java.net.SocketTimeoutException e
      (throw (timeout-error
               provider-name
               (or (.getMessage e) "Request timeout")
               :cause e)))
    (catch java.net.ConnectException e
      (throw (connection-error
               provider-name
               (or (.getMessage e) "Connection refused")
               :cause e)))
    (catch java.net.UnknownHostException e
      (throw (connection-error
               provider-name
               (str "Unknown host: " (.getMessage e))
               :cause e)))
    (catch java.io.IOException e
      (throw (connection-error
               provider-name
               (or (.getMessage e) "I/O error")
               :cause e)))
    (catch javax.net.ssl.SSLException e
      (throw (connection-error
               provider-name
               (str "SSL error: " (.getMessage e))
               :cause e)))
    (catch clojure.lang.ExceptionInfo e
      ;; Re-throw if already a litellm error
      (if (litellm-error? e)
        (throw e)
        ;; Hato throws ExceptionInfo with HTTP response in ex-data
        (let [data (ex-data e)]
          (if-let [status (:status data)]
            ;; HTTP error from hato - extract response details
            (let [headers (:headers data)
                  body-str (:body data)
                  ;; Try to parse body as JSON
                  body (if (string? body-str)
                         (try
                           (cheshire.core/decode body-str true)
                           (catch Exception _
                             body-str))
                         body-str)
                  request-id (get headers "x-request-id")
                  error-info (if (map? body) (get body :error {}) {})
                  message (or (:message error-info) 
                             (.getMessage e)
                             (str "HTTP " status))
                  provider-code (or (:code error-info) (:type error-info))]
              (throw (http-status->error 
                       status 
                       provider-name 
                       message
                       :provider-code provider-code
                       :request-id request-id
                       :body body)))
            ;; Not an HTTP error - wrap as provider error
            (throw (provider-error
                     provider-name
                     (or (.getMessage e) "HTTP error")
                     :cause e))))))
    (catch Exception e
      (throw (provider-error
              provider-name
              (or (.getMessage e) "Unexpected error")
              :cause e)))))

;; ============================================================================
;; HTTP Status Code Mapping
;; ============================================================================

(defn http-status->error
  "Map HTTP status code to appropriate error constructor"
  [status provider message & {:keys [provider-code retry-after request-id body]
                              :as opts}]
  (case status
    400 (invalid-request message :context {:http-status status :body body})
    401 (authentication-error provider message :http-status status :provider-code provider-code)
    403 (authorization-error provider message :http-status status :provider-code provider-code)
    404 (model-not-found provider (or (:model body) "unknown") 
                        :http-status status :provider-code provider-code)
    429 (if (and body (str/includes? (str body) "quota"))
          (quota-exceeded provider message :http-status status :provider-code provider-code)
          (rate-limit provider message :http-status status :provider-code provider-code 
                     :retry-after retry-after :request-id request-id))
    408 (timeout-error provider message :request-id request-id)
    (500 502 503 504) (server-error provider message :http-status status 
                                    :provider-code provider-code :request-id request-id)
    ;; Default to provider error
    (provider-error provider message :http-status status :provider-code provider-code 
                   :request-id request-id :recoverable? (>= status 500))))
