(ns litellm.providers.bedrock
  "AWS Amazon Bedrock provider implementation for LiteLLM.
   
   Bedrock is a managed AWS service that provides access to multiple AI models
   through the unified Converse API, including:
   - Anthropic Claude models
   - Amazon Nova models  
   - Meta Llama models
   - Mistral models
   - And more
   
   Authentication is handled via AWS credentials (environment variables,
   IAM roles, or explicit configuration)."
  (:require [litellm.streaming :as streaming]
            [litellm.errors :as errors]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.async :as async]))

;; ============================================================================
;; AWS Client Management
;; ============================================================================

(defonce ^:private clients-atom (atom {}))

(defn- get-or-create-client
  "Get or create an AWS Bedrock Runtime client for the given region."
  [config]
  (let [region (or (:region config)
                   (System/getenv "AWS_REGION")
                   (System/getenv "AWS_DEFAULT_REGION")
                   "us-east-1")
        client-key (keyword region)]
    (if-let [client (get @clients-atom client-key)]
      client
      (let [client-opts (cond-> {:api :bedrock-runtime
                                 :region region}
                          (:access-key-id config)
                          (assoc :credentials-provider
                                 (reify credentials/CredentialsProvider
                                   (fetch [_]
                                     {:aws/access-key-id (:access-key-id config)
                                      :aws/secret-access-key (:secret-access-key config)
                                      :aws/session-token (:session-token config)}))))
            client (aws/client client-opts)]
        (swap! clients-atom assoc client-key client)
        client))))

;; ============================================================================
;; Message Transformations
;; ============================================================================

(defn transform-content
  "Transform content to Bedrock format (array of content blocks)."
  [content]
  (cond
    (string? content)
    [{:text content}]

    (vector? content)
    (mapv (fn [block]
            (cond
              (string? block) {:text block}
              (map? block) (case (:type block)
                             "text" {:text (:text block)}
                             "image_url" {:image {:format (or (:format block) "png")
                                                  :source {:bytes (:url (:image_url block))}}}
                             block)
              :else block))
          content)

    :else [{:text (str content)}]))

(defn transform-messages
  "Transform messages to Bedrock Converse format."
  [messages]
  (let [system-messages (filter #(= :system (:role %)) messages)
        other-messages (remove #(= :system (:role %)) messages)]
    {:system (when (seq system-messages)
               (mapv (fn [msg] {:text (:content msg)}) system-messages))
     :messages (mapv (fn [msg]
                       (let [role (case (:role msg)
                                    :user "user"
                                    :assistant "assistant"
                                    :tool "user"
                                    (name (:role msg)))]
                         (cond
                           ;; Tool result message
                           (= :tool (:role msg))
                           {:role "user"
                            :content [{:toolResult
                                       {:toolUseId (:tool-call-id msg)
                                        :content [{:text (:content msg)}]}}]}

                           ;; Assistant message with tool calls
                           (and (= :assistant (:role msg)) (:tool-calls msg))
                           {:role "assistant"
                            :content (vec (concat
                                           ;; Text content if present
                                           (when (:content msg)
                                             [{:text (:content msg)}])
                                           ;; Tool uses
                                           (map (fn [tool-call]
                                                  {:toolUse
                                                   {:toolUseId (:id tool-call)
                                                    :name (get-in tool-call [:function :name])
                                                    :input (json/decode (get-in tool-call [:function :arguments]) true)}})
                                                (:tool-calls msg))))}

                           ;; Regular message
                           :else
                           {:role role
                            :content (transform-content (:content msg))})))
                     other-messages)}))

(defn transform-tools
  "Transform tools to Bedrock toolConfig format."
  [tools]
  (when (seq tools)
    {:tools (mapv (fn [tool]
                    (let [func (:function tool)]
                      {:toolSpec
                       {:name (or (:function-name func) (:name func))
                        :description (or (:function-description func) (:description func))
                        :inputSchema {:json (or (:function-parameters func)
                                                (:parameters func)
                                                {})}}}))
                  tools)}))

(defn transform-tool-choice
  "Transform tool choice to Bedrock format."
  [tool-choice]
  (cond
    (= tool-choice :auto) {:auto {}}
    (= tool-choice :any) {:any {}}
    (= tool-choice :none) nil ;; Bedrock doesn't have explicit 'none', just omit tools
    (map? tool-choice) {:tool {:name (:name tool-choice)}}
    :else {:auto {}}))

;; ============================================================================
;; Response Transformations
;; ============================================================================

(defn transform-tool-uses
  "Transform Bedrock toolUse responses to standard format."
  [content]
  (when-let [tool-uses (seq (filter #(contains? % :toolUse) content))]
    (vec (map (fn [block]
                (let [tool-use (:toolUse block)]
                  {:id (:toolUseId tool-use)
                   :type "function"
                   :function {:name (:name tool-use)
                              :arguments (json/encode (:input tool-use))}}))
              tool-uses))))

(defn extract-text-content
  "Extract text content from Bedrock response content blocks."
  [content]
  (when-let [text-blocks (seq (filter #(contains? % :text) content))]
    (str/join "" (map :text text-blocks))))

(defn transform-stop-reason
  "Transform Bedrock stop reason to standard finish reason."
  [stop-reason]
  (case stop-reason
    "end_turn" :stop
    "tool_use" :tool_calls
    "max_tokens" :length
    "stop_sequence" :stop
    "content_filtered" :content_filter
    "guardrail_intervened" :content_filter
    :stop))

(defn transform-usage
  "Transform Bedrock usage to standard format."
  [usage]
  (when usage
    {:prompt-tokens (or (:inputTokens usage) 0)
     :completion-tokens (or (:outputTokens usage) 0)
     :total-tokens (+ (or (:inputTokens usage) 0)
                      (or (:outputTokens usage) 0))}))

(defn transform-choice
  "Transform Bedrock response to standard choice format."
  [response index]
  (let [message (get-in response [:output :message])
        content (:content message)
        text-content (extract-text-content content)
        tool-calls (transform-tool-uses content)]
    {:index index
     :message {:role :assistant
               :content text-content
               :tool-calls tool-calls}
     :finish-reason (transform-stop-reason (:stopReason response))}))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn handle-error-response
  "Handle Bedrock API error responses."
  [provider response]
  (let [error-type (:__type response)
        message (or (:message response)
                    (:Message response)
                    "Unknown Bedrock error")
        status (cond
                 (str/includes? (str error-type) "AccessDenied") 403
                 (str/includes? (str error-type) "Validation") 400
                 (str/includes? (str error-type) "ResourceNotFound") 404
                 (str/includes? (str error-type) "Throttling") 429
                 (str/includes? (str error-type) "ServiceUnavailable") 503
                 (str/includes? (str error-type) "ModelTimeout") 408
                 (str/includes? (str error-type) "ModelError") 424
                 :else 500)]
    (throw (errors/http-status->error
            status
            "bedrock"
            message
            :provider-code error-type
            :body response))))

;; ============================================================================
;; Model and Cost Configuration
;; ============================================================================

(def default-cost-map
  "Default cost per token for Bedrock models (in USD).
   Note: Prices may vary by region and commitment."
  {;; Anthropic Claude models
   "anthropic.claude-3-5-sonnet-20241022-v2:0" {:input 0.000003 :output 0.000015}
   "anthropic.claude-3-5-sonnet-20240620-v1:0" {:input 0.000003 :output 0.000015}
   "anthropic.claude-3-5-haiku-20241022-v1:0" {:input 0.0000008 :output 0.000004}
   "anthropic.claude-3-opus-20240229-v1:0" {:input 0.000015 :output 0.000075}
   "anthropic.claude-3-sonnet-20240229-v1:0" {:input 0.000003 :output 0.000015}
   "anthropic.claude-3-haiku-20240307-v1:0" {:input 0.00000025 :output 0.00000125}
   ;; Amazon Nova models
   "amazon.nova-pro-v1:0" {:input 0.0000008 :output 0.0000032}
   "amazon.nova-lite-v1:0" {:input 0.00000006 :output 0.00000024}
   "amazon.nova-micro-v1:0" {:input 0.000000035 :output 0.00000014}
   ;; Meta Llama models
   "meta.llama3-1-70b-instruct-v1:0" {:input 0.00000099 :output 0.00000099}
   "meta.llama3-1-8b-instruct-v1:0" {:input 0.00000022 :output 0.00000022}
   "meta.llama3-2-90b-instruct-v1:0" {:input 0.000002 :output 0.000002}
   "meta.llama3-2-11b-instruct-v1:0" {:input 0.00000016 :output 0.00000016}
   ;; Mistral models
   "mistral.mistral-large-2407-v1:0" {:input 0.000002 :output 0.000006}
   "mistral.mistral-small-2402-v1:0" {:input 0.0000001 :output 0.0000003}})

(def default-model-mapping
  "Model aliases for convenience."
  {"claude-3-5-sonnet" "anthropic.claude-3-5-sonnet-20241022-v2:0"
   "claude-3-5-haiku" "anthropic.claude-3-5-haiku-20241022-v1:0"
   "claude-3-opus" "anthropic.claude-3-opus-20240229-v1:0"
   "claude-3-sonnet" "anthropic.claude-3-sonnet-20240229-v1:0"
   "claude-3-haiku" "anthropic.claude-3-haiku-20240307-v1:0"
   "nova-pro" "amazon.nova-pro-v1:0"
   "nova-lite" "amazon.nova-lite-v1:0"
   "nova-micro" "amazon.nova-micro-v1:0"
   "llama-3-1-70b" "meta.llama3-1-70b-instruct-v1:0"
   "llama-3-1-8b" "meta.llama3-1-8b-instruct-v1:0"
   "llama-3-2-90b" "meta.llama3-2-90b-instruct-v1:0"
   "mistral-large" "mistral.mistral-large-2407-v1:0"
   "mistral-small" "mistral.mistral-small-2402-v1:0"})

;; ============================================================================
;; Bedrock Provider Implementation Functions
;; ============================================================================

(defn extract-model-name
  "Extract actual model name from model string (removes bedrock/ prefix if present)."
  [model]
  (if (string? model)
    (let [parts (str/split model #"/")]
      (if (and (> (count parts) 1)
               (= "bedrock" (first parts)))
        (str/join "/" (rest parts))
        model))
    (str model)))

(defn transform-request-impl
  "Bedrock-specific transform-request implementation."
  [provider-name request config]
  (let [model (extract-model-name (:model request))
        mapped-model (get (:model-mapping config default-model-mapping) model model)
        messages-data (transform-messages (:messages request))
        inference-config (cond-> {}
                           (:max-tokens request) (assoc :maxTokens (:max-tokens request))
                           (:temperature request) (assoc :temperature (:temperature request))
                           (:top-p request) (assoc :topP (:top-p request))
                           (:stop request) (assoc :stopSequences (:stop request)))]
    (cond-> {:modelId mapped-model
             :messages (:messages messages-data)}
      (seq (:system messages-data)) (assoc :system (:system messages-data))
      (seq inference-config) (assoc :inferenceConfig inference-config)
      (:tools request) (merge (transform-tools (:tools request)))
      (:tool-choice request) (assoc :toolConfig
                                    (merge (or (transform-tools (:tools request)) {})
                                           {:toolChoice (transform-tool-choice (:tool-choice request))})))))

(defn make-request-impl
  "Bedrock-specific make-request implementation using AWS SDK."
  [provider-name transformed-request thread-pool telemetry config]
  (let [client (get-or-create-client config)
        model-id (:modelId transformed-request)
        request-body (dissoc transformed-request :modelId)]
    (errors/wrap-http-errors
     "bedrock"
     #(let [start-time (System/currentTimeMillis)
            response (aws/invoke client {:op :Converse
                                         :request (assoc request-body :modelId model-id)})
            duration (- (System/currentTimeMillis) start-time)]
        (log/debug "Bedrock request completed" {:model model-id :duration-ms duration})

        ;; Check for errors
        (when (:cognitect.anomalies/category response)
          (handle-error-response :bedrock response))

        ;; Return a deref-able future-like object for consistency
        (reify clojure.lang.IDeref
          (deref [_] {:body response :status 200}))))))

(defn transform-response-impl
  "Bedrock-specific transform-response implementation."
  [provider-name response]
  (let [body (:body response)]
    {:id (str "bedrock-" (System/currentTimeMillis))
     :object "chat.completion"
     :created (quot (System/currentTimeMillis) 1000)
     :model (get-in body [:modelId] "bedrock")
     :choices [(transform-choice body 0)]
     :usage (transform-usage (:usage body))}))

(defn supports-streaming-impl
  "Bedrock-specific supports-streaming? implementation."
  [provider-name]
  true)

(defn supports-function-calling-impl
  "Bedrock-specific supports-function-calling? implementation."
  [provider-name]
  true)

(defn get-rate-limits-impl
  "Bedrock-specific get-rate-limits implementation.
   Note: Actual limits depend on your AWS account quotas."
  [provider-name]
  {:requests-per-minute 100
   :tokens-per-minute 100000})

(defn health-check-impl
  "Bedrock-specific health-check implementation."
  [provider-name thread-pool config]
  (try
    (let [client (get-or-create-client config)]
      ;; Try to invoke a simple operation to check connectivity
      (aws/invoke client {:op :ListFoundationModels
                          :request {}})
      true)
    (catch Exception e
      (log/warn "Bedrock health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "Bedrock-specific get-cost-per-token implementation."
  [provider-name model]
  (let [model-name (extract-model-name model)
        mapped-model (get default-model-mapping model-name model-name)]
    (get default-cost-map mapped-model {:input 0.0 :output 0.0})))

;; ============================================================================
;; Streaming Support
;; ============================================================================

(defn transform-streaming-chunk-impl
  "Bedrock-specific transform-streaming-chunk implementation."
  [provider-name chunk]
  (let [event-type (first (keys chunk))]
    (case event-type
      :contentBlockStart
      (let [block (get-in chunk [:contentBlockStart :start])]
        (cond
          ;; Tool use start
          (contains? block :toolUse)
          {:id (str "bedrock-" (System/currentTimeMillis))
           :object "chat.completion.chunk"
           :created (quot (System/currentTimeMillis) 1000)
           :choices [{:index 0
                      :delta {:role :assistant
                              :tool-calls [{:id (get-in block [:toolUse :toolUseId])
                                            :type "function"
                                            :function {:name (get-in block [:toolUse :name])
                                                       :arguments ""}}]}
                      :finish-reason nil}]}
          :else nil))

      :contentBlockDelta
      (let [delta (get-in chunk [:contentBlockDelta :delta])]
        (cond
          ;; Text delta
          (contains? delta :text)
          {:id (str "bedrock-" (System/currentTimeMillis))
           :object "chat.completion.chunk"
           :created (quot (System/currentTimeMillis) 1000)
           :choices [{:index 0
                      :delta {:role :assistant
                              :content (:text delta)}
                      :finish-reason nil}]}

          ;; Tool input delta
          (contains? delta :toolUse)
          {:id (str "bedrock-" (System/currentTimeMillis))
           :object "chat.completion.chunk"
           :created (quot (System/currentTimeMillis) 1000)
           :choices [{:index 0
                      :delta {:tool-calls [{:function {:arguments (get-in delta [:toolUse :input])}}]}
                      :finish-reason nil}]}

          :else nil))

      :messageStop
      {:id (str "bedrock-" (System/currentTimeMillis))
       :object "chat.completion.chunk"
       :created (quot (System/currentTimeMillis) 1000)
       :choices [{:index 0
                  :delta {}
                  :finish-reason (transform-stop-reason (:stopReason chunk))}]}

      :metadata
      nil ;; Skip metadata events

      nil)))

(defn make-streaming-request-impl
  "Bedrock-specific make-streaming-request implementation."
  [provider-name transformed-request thread-pool config]
  (let [client (get-or-create-client config)
        model-id (:modelId transformed-request)
        request-body (dissoc transformed-request :modelId)
        output-ch (streaming/create-stream-channel)]

    (async/thread
      (try
        (log/debug "Making Bedrock streaming request" {:model model-id})
        (let [response (aws/invoke client {:op :ConverseStream
                                           :request (assoc request-body :modelId model-id)})]

          ;; Check for errors
          (when (:cognitect.anomalies/category response)
            (async/>!! output-ch (streaming/stream-error "bedrock"
                                                         (or (:message response) "Stream error")))
            (streaming/close-stream! output-ch))

          ;; Process streaming events
          (when-let [stream (:stream response)]
            (doseq [event stream]
              (when-let [transformed (transform-streaming-chunk-impl :bedrock event)]
                (async/>!! output-ch transformed)))
            (streaming/close-stream! output-ch)))

        (catch Exception e
          (log/error "Error in Bedrock streaming request" {:error (.getMessage e)})
          (async/>!! output-ch (streaming/stream-error "bedrock" (.getMessage e)))
          (streaming/close-stream! output-ch))))

    output-ch))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn list-models
  "List available Bedrock foundation models."
  [config]
  (try
    (let [client (get-or-create-client config)
          response (aws/invoke client {:op :ListFoundationModels
                                       :request {}})]
      (if (:cognitect.anomalies/category response)
        (do
          (log/error "Failed to list Bedrock models" response)
          [])
        (map :modelId (:modelSummaries response))))
    (catch Exception e
      (log/error "Error listing Bedrock models" e)
      [])))

(defn test-bedrock-connection
  "Test Bedrock connection with a simple request."
  [config]
  (let [test-request {:model "anthropic.claude-3-haiku-20240307-v1:0"
                      :messages [{:role :user :content "Hello"}]
                      :max-tokens 5}]
    (try
      (let [transformed (transform-request-impl :bedrock test-request config)
            response @(make-request-impl :bedrock transformed nil nil config)
            standard-response (transform-response-impl :bedrock response)]
        {:success true
         :provider "bedrock"
         :model (:model test-request)
         :response-id (:id standard-response)
         :usage (:usage standard-response)})
      (catch Exception e
        {:success false
         :provider "bedrock"
         :error (.getMessage e)
         :error-type (type e)}))))
