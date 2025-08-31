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

(def Message
  [:map
   [:role Role]
   [:content :string]
   [:name {:optional true} :string]
   [:tool-call-id {:optional true} :string]])

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
(def FunctionParameters :map)

(def Function
  [:map
   [:function-name FunctionName]
   [:function-description FunctionDescription]
   [:function-parameters {:optional true} FunctionParameters]])

(def Functions [:vector Function])
(def FunctionCall [:or [:enum :auto :none] Function])

;; Tools (newer function calling format)
(def ToolType [:enum "function"])
(def Tool
  [:map
   [:tool-type ToolType]
   [:function Function]])

(def Tools [:vector Tool])
(def ToolChoice [:or [:enum :auto :none :required]])

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
   [:functions {:optional true} Functions]
   [:function-call {:optional true} FunctionCall]
   [:tools {:optional true} Tools]
   [:tool-choice {:optional true} ToolChoice]])

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
(def FinishReason [:enum :stop :length :function_call :tool_calls :content_filter])

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

(def ThreadPoolsConfig
  [:map
   [:api-calls {:optional true} ThreadPoolConfig]
   [:cache-ops {:optional true} ThreadPoolConfig]
   [:retries {:optional true} ThreadPoolConfig]
   [:health-checks {:optional true} ThreadPoolConfig]
   [:monitoring {:optional true} ThreadPoolConfig]])

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
   [:thread-pools-config {:optional true} ThreadPoolsConfig]
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
  (-> request
      (m/decode CompletionRequest json-transformer)
      validate-request!))

(defn transform-response
  "Transform and validate response to external format"
  [response]
  (-> response
      validate-response!
      (m/encode CompletionResponse json-transformer)))

(defn transform-config
  "Transform and validate config from external format"
  [config]
  (-> config
      (m/decode SystemConfig json-transformer)
      validate-config!))
