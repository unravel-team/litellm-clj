(ns litellm.schemas
  "Malli schemas for LiteLLM library"
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

;; ============================================================================
;; Message Schemas
;; ============================================================================

(def Role
  [:enum :user :assistant :system :tool])

(def KeywordAnyMap
  [:map-of :keyword :any])

(def ContentPart
  "OpenAI-compatible multimodal content part. Providers may add keys."
  KeywordAnyMap)

(def MessageContent
  [:maybe [:or :string [:vector ContentPart]]])

(def ToolCall
  KeywordAnyMap)

(def Message
  [:map
   [:role Role]
   [:content {:optional true} MessageContent]
   [:name {:optional true} :string]
   [:tool-call-id {:optional true} :string]
   [:reasoning-content {:optional true} :string]
   [:tool-calls {:optional true} [:vector ToolCall]]
   [:partial {:optional true} :boolean]
   [:thinking-blocks {:optional true} [:vector :map]]])  ; Thinking blocks (Anthropic-specific)

(def Messages
  [:vector {:min 1} Message])

;; ============================================================================
;; Model and Provider Schemas
;; ============================================================================

(def Model :string)
(def Provider :keyword)
(def ApiKey :string)
(def ApiBase :string)

;; ============================================================================
;; Request Parameters
;; ============================================================================

(def MaxTokens :int)
(def Temperature [:double {:min 0 :max 2}])
(def TopP [:double {:min 0 :max 1}])
(def FrequencyPenalty [:double {:min -2 :max 2}])
(def PresencePenalty [:double {:min -2 :max 2}])
(def Stream :boolean)
(def Stop [:or :string [:vector :string]])

;; Function calling
(def FunctionName :string)
(def FunctionDescription :string)
(def FunctionParameters KeywordAnyMap)

(def Function
  KeywordAnyMap)

;; Tools (newer function calling format and existing legacy shape)
(def ToolType [:enum "function"])
(def CanonicalTool
  [:map
   [:type ToolType]
   [:function Function]])
(def LegacyTool
  [:map
   [:tool-type ToolType]
   [:function Function]])
(def Tool
  [:or CanonicalTool LegacyTool])

(def Tools [:vector Tool])
(def ToolChoice
  [:or
   [:enum :auto :none :required :any]
   [:enum "auto" "none" "required" "any"]
   KeywordAnyMap])

;; Provider-specific passthrough fields
(def ResponseFormat KeywordAnyMap)
(def StreamOptions KeywordAnyMap)
(def ExtraBody KeywordAnyMap)

;; Reasoning parameters
(def ReasoningEffort
  [:enum :minimal :none :low :medium :high :xhigh :max
   "minimal" "none" "low" "medium" "high" "xhigh" "max"])
(def ThinkingType [:enum :enabled :disabled "enabled" "disabled"])
(def ThinkingKeep [:enum :all "all"])
(def ThinkingConfig
  [:map
   [:type ThinkingType]
   [:budget-tokens {:optional true} :int]
   [:keep {:optional true} ThinkingKeep]
   [:clear-thinking {:optional true} :boolean]])

;; ============================================================================
;; Request Schema
;; ============================================================================

(def CompletionRequest
  [:map
   [:model Model]
   [:messages Messages]
   [:api-key {:optional true} ApiKey]
   [:api-base {:optional true} ApiBase]
   [:max-tokens {:optional true} MaxTokens]
   [:temperature {:optional true} Temperature]
   [:top-p {:optional true} TopP]
   [:frequency-penalty {:optional true} FrequencyPenalty]
   [:presence-penalty {:optional true} PresencePenalty]
   [:stream {:optional true} Stream]
   [:stop {:optional true} Stop]
   [:tools {:optional true} Tools]
   [:tool-choice {:optional true} ToolChoice]
   [:reasoning-effort {:optional true} ReasoningEffort]
   [:thinking {:optional true} ThinkingConfig]
   [:response-format {:optional true} ResponseFormat]
   [:stream-options {:optional true} StreamOptions]
   [:do-sample {:optional true} :boolean]
   [:tool-stream {:optional true} :boolean]
   [:prompt-cache-key {:optional true} :string]
   [:safety-identifier {:optional true} :string]
   [:request-id {:optional true} :string]
   [:user-id {:optional true} :string]
   [:extra-body {:optional true} ExtraBody]])

;; ============================================================================
;; Response Schemas
;; ============================================================================

(def Id :string)
(def object :string)
(def Created :int)
(def ModelResponse :string)

;; Usage information
(def PromptTokens [:maybe :int])
(def CompletionTokens [:maybe :int])
(def TotalTokens [:maybe :int])

(def Usage
  [:map
   [:prompt-tokens PromptTokens]
   [:completion-tokens CompletionTokens]
   [:total-tokens TotalTokens]])

;; Choice information
(def Index :int)
(def FinishReason [:or :keyword :string])

(def Choice
  [:map
   [:index Index]
   [:message Message]
   [:finish-reason FinishReason]])

(def Choices [:vector Choice])

;; Complete response
(def CompletionResponse
  [:map
   [:id Id]
   [:object Object]
   [:created Created]
   [:model ModelResponse]
   [:choices Choices]
   [:usage Usage]])

;; ============================================================================
;; Embedding Schemas
;; ============================================================================

(def Input [:or :string [:vector :string]])
(def EncodingFormat [:enum :float :base64])
(def Dimensions :int)

(def EmbeddingRequest
  [:map
   [:model Model]
   [:input Input]
   [:api-key {:optional true} ApiKey]
   [:api-base {:optional true} ApiBase]
   [:encoding-format {:optional true} EncodingFormat]
   [:dimensions {:optional true} Dimensions]
   [:user {:optional true} :string]
   [:timeout {:optional true} :int]
   [:input-type {:optional true} [:enum :query :passage :text :image :video :audio]]])

(def EmbeddingObject
  [:map
   [:object :string]
   [:embedding [:vector :double]]
   [:index :int]])

(def EmbeddingResponse
  [:map
   [:object :string]
   [:data [:vector EmbeddingObject]]
   [:model :string]
   [:usage Usage]])

;; ============================================================================
;; Provider Configuration
;; ============================================================================

(def RateLimit :int)
(def Timeout :int)
(def MaxRetries :int)

(def ProviderConfig
  [:map
   [:provider Provider]
   [:api-key ApiKey]
   [:api-base {:optional true} ApiBase]
   [:rate-limit {:optional true} RateLimit]
   [:timeout {:optional true} Timeout]
   [:max-retries {:optional true} MaxRetries]])

;; ============================================================================
;; Router Configuration
;; ============================================================================

(def RoutingStrategy
  [:enum :round-robin :usage-based :latency-based :random :weighted])

(def FallbackModel :string)
(def Fallbacks [:map-of :string [:vector FallbackModel]])

(def RetryAttempts :int)
(def BackoffMs :int)
(def MaxBackoffMs :int)

(def RetryConfig
  [:map
   [:retry-attempts {:optional true} RetryAttempts]
   [:backoff-ms {:optional true} BackoffMs]
   [:max-backoff-ms {:optional true} MaxBackoffMs]])

(def RouterConfig
  [:map
   [:routing-strategy RoutingStrategy]
   [:fallbacks {:optional true} Fallbacks]
   [:retry-config {:optional true} RetryConfig]])

;; ============================================================================
;; Thread Pool Configuration
;; ============================================================================

(def PoolSize :int)
(def QueueSize :int)

(def ThreadPoolConfig
  [:map
   [:pool-size {:optional true} PoolSize]
   [:queue-size {:optional true} QueueSize]])

;; ============================================================================
;; Cache Configuration
;; ============================================================================

(def CacheType [:enum :memory :redis :s3])
(def TtlSeconds :int)
(def MaxSize :int)

(def CacheConfig
  [:map
   [:cache-type CacheType]
   [:ttl-seconds {:optional true} TtlSeconds]
   [:max-size {:optional true} MaxSize]])

;; ============================================================================
;; System Configuration
;; ============================================================================

(def SystemConfig
  [:map
   [:providers {:optional true} [:map-of :string ProviderConfig]]
   [:router-config {:optional true} RouterConfig]
   [:thread-pool-config {:optional true} ThreadPoolConfig]
   [:cache-config {:optional true} CacheConfig]])

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn valid-request?
  "Check if a completion request is valid"
  [request]
  (m/validate CompletionRequest request))

(defn explain-request
  "Explain what's wrong with a completion request"
  [request]
  (when-not (valid-request? request)
    (me/humanize (m/explain CompletionRequest request))))

(defn valid-response?
  "Check if a completion response is valid"
  [response]
  (m/validate CompletionResponse response))

(defn explain-response
  "Explain what's wrong with a completion response"
  [response]
  (when-not (valid-response? response)
    (me/humanize (m/explain CompletionResponse response))))

(defn valid-config?
  "Check if system configuration is valid"
  [config]
  (m/validate SystemConfig config))

(defn explain-config
  "Explain what's wrong with system configuration"
  [config]
  (when-not (valid-config? config)
    (me/humanize (m/explain SystemConfig config))))

(defn valid-embedding-request?
  "Check if an embedding request is valid"
  [request]
  (m/validate EmbeddingRequest request))

(defn explain-embedding-request
  "Explain what's wrong with an embedding request"
  [request]
  (when-not (valid-embedding-request? request)
    (me/humanize (m/explain EmbeddingRequest request))))

(defn valid-embedding-response?
  "Check if an embedding response is valid"
  [response]
  (m/validate EmbeddingResponse response))

(defn explain-embedding-response
  "Explain what's wrong with an embedding response"
  [response]
  (when-not (valid-embedding-response? response)
    (me/humanize (m/explain EmbeddingResponse response))))

(defn validate-request!
  "Validate request and throw exception if invalid"
  [request]
  (when-not (valid-request? request)
    (throw (ex-info "Invalid request"
                    {:type :validation-error
                     :errors (explain-request request)})))
  request)

(defn validate-response!
  "Validate response and throw exception if invalid"
  [response]
  (when-not (valid-response? response)
    (throw (ex-info "Invalid response"
                    {:type :validation-error
                     :errors (explain-response response)})))
  response)

(defn validate-config!
  "Validate config and throw exception if invalid"
  [config]
  (when-not (valid-config? config)
    (throw (ex-info "Invalid configuration"
                    {:type :validation-error
                     :errors (explain-config config)})))
  config)

(defn validate-embedding-request!
  "Validate embedding request and throw exception if invalid"
  [request]
  (when-not (valid-embedding-request? request)
    (throw (ex-info "Invalid embedding request"
                    {:type :validation-error
                     :errors (explain-embedding-request request)})))
  request)

(defn validate-embedding-response!
  "Validate embedding response and throw exception if invalid"
  [response]
  (when-not (valid-embedding-response? response)
    (throw (ex-info "Invalid embedding response"
                    {:type :validation-error
                     :errors (explain-embedding-response response)})))
  response)

;; ============================================================================
;; Transformation Helpers
;; ============================================================================

(def string-transformer
  (mt/transformer
    mt/string-transformer
    mt/strip-extra-keys-transformer))

(def json-transformer
  (mt/transformer
    mt/json-transformer
    mt/strip-extra-keys-transformer))

(defn transform-request
  "Transform and validate request from external format"
  [request]
  (->> (m/decode CompletionRequest request json-transformer)
       validate-request!))

(defn transform-response
  "Transform and validate response to external format"
  [response]
  (let [validated (validate-response! response)]
    (m/encode CompletionResponse validated json-transformer)))

(defn transform-config
  "Transform and validate config from external format"
  [config]
  (->> (m/decode SystemConfig config json-transformer)
       validate-config!))
