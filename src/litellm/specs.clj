(ns litellm.specs
  "Data specifications for LiteLLM library"
  (:require [clojure.spec.alpha :as s]))

;; ============================================================================
;; Message Specifications
;; ============================================================================

(s/def ::role #{:user :assistant :system :tool})
(s/def ::content string?)
(s/def ::name string?)
(s/def ::tool-call-id string?)

(s/def ::message 
  (s/keys :req-un [::role ::content]
          :opt-un [::name ::tool-call-id]))

(s/def ::messages 
  (s/coll-of ::message :min-count 1))

;; ============================================================================
;; Model and Provider Specifications
;; ============================================================================

(s/def ::model string?)
(s/def ::provider keyword?)
(s/def ::api-key string?)
(s/def ::api-base string?)

;; ============================================================================
;; Request Parameters
;; ============================================================================

(s/def ::max-tokens pos-int?)
(s/def ::temperature (s/and number? #(<= 0 % 2)))
(s/def ::top-p (s/and number? #(<= 0 % 1)))
(s/def ::frequency-penalty (s/and number? #(<= -2 % 2)))
(s/def ::presence-penalty (s/and number? #(<= -2 % 2)))
(s/def ::stream boolean?)
(s/def ::stop (s/or :string string? :strings (s/coll-of string?)))

;; Function calling
(s/def ::function-name string?)
(s/def ::function-description string?)
(s/def ::function-parameters map?)

(s/def ::function
  (s/keys :req-un [::function-name ::function-description]
          :opt-un [::function-parameters]))

(s/def ::functions (s/coll-of ::function))
(s/def ::function-call (s/or :auto #{:auto} :none #{:none} :function ::function))

;; Tools (newer function calling format)
(s/def ::tool-type #{"function"})
(s/def ::tool
  (s/keys :req-un [::tool-type ::function]))

(s/def ::tools (s/coll-of ::tool))
(s/def ::tool-choice (s/or :auto #{:auto} :none #{:none} :required #{:required}))

;; ============================================================================
;; Request Specification
;; ============================================================================

(s/def ::completion-request
  (s/keys :req-un [::model ::messages]
          :opt-un [::api-key ::api-base ::max-tokens ::temperature ::top-p
                   ::frequency-penalty ::presence-penalty ::stream ::stop
                   ::functions ::function-call ::tools ::tool-choice]))

;; ============================================================================
;; Response Specifications
;; ============================================================================

(s/def ::id string?)
(s/def ::object string?)
(s/def ::created pos-int?)
(s/def ::model-response string?)

;; Usage information
(s/def ::prompt-tokens (s/nilable nat-int?))
(s/def ::completion-tokens (s/nilable nat-int?))
(s/def ::total-tokens (s/nilable nat-int?))

(s/def ::usage
  (s/keys :req-un [::prompt-tokens ::completion-tokens ::total-tokens]))

;; Choice information
(s/def ::index nat-int?)
(s/def ::finish-reason #{:stop :length :function_call :tool_calls :content_filter})

(s/def ::choice
  (s/keys :req-un [::index ::message ::finish-reason]))

(s/def ::choices (s/coll-of ::choice))

;; Complete response
(s/def ::completion-response
  (s/keys :req-un [::id ::object ::created ::model-response ::choices ::usage]))

;; ============================================================================
;; Provider Configuration
;; ============================================================================

(s/def ::rate-limit pos-int?)
(s/def ::timeout pos-int?)
(s/def ::max-retries nat-int?)

(s/def ::provider-config
  (s/keys :req-un [::provider ::api-key]
          :opt-un [::api-base ::rate-limit ::timeout ::max-retries]))

;; ============================================================================
;; Router Configuration
;; ============================================================================

(s/def ::routing-strategy 
  #{:round-robin :usage-based :latency-based :random :weighted})

(s/def ::fallback-model string?)
(s/def ::fallbacks 
  (s/map-of string? (s/coll-of ::fallback-model)))

(s/def ::retry-attempts pos-int?)
(s/def ::backoff-ms pos-int?)
(s/def ::max-backoff-ms pos-int?)

(s/def ::retry-config
  (s/keys :opt-un [::retry-attempts ::backoff-ms ::max-backoff-ms]))

(s/def ::router-config
  (s/keys :req-un [::routing-strategy]
          :opt-un [::fallbacks ::retry-config]))

;; ============================================================================
;; Thread Pool Configuration
;; ============================================================================

(s/def ::pool-size pos-int?)
(s/def ::queue-size pos-int?)

(s/def ::thread-pool-config
  (s/keys :opt-un [::pool-size ::queue-size]))

(s/def ::thread-pools-config
  (s/keys :opt-un [::api-calls ::cache-ops ::retries ::health-checks ::monitoring]))

;; ============================================================================
;; Cache Configuration
;; ============================================================================

(s/def ::cache-type #{:memory :redis :s3})
(s/def ::ttl-seconds pos-int?)
(s/def ::max-size pos-int?)

(s/def ::cache-config
  (s/keys :req-un [::cache-type]
          :opt-un [::ttl-seconds ::max-size]))

;; ============================================================================
;; System Configuration
;; ============================================================================

(s/def ::system-config
  (s/keys :opt-un [::providers ::router-config ::thread-pools-config 
                   ::cache-config]))

;; ============================================================================
;; Validation Helpers
;; ============================================================================

(defn valid-request?
  "Check if a completion request is valid"
  [request]
  (s/valid? ::completion-request request))

(defn explain-request
  "Explain what's wrong with a completion request"
  [request]
  (s/explain ::completion-request request))

(defn valid-response?
  "Check if a completion response is valid"
  [response]
  (s/valid? ::completion-response response))

(defn explain-response
  "Explain what's wrong with a completion response"
  [response]
  (s/explain ::completion-response response))

(defn valid-config?
  "Check if system configuration is valid"
  [config]
  (s/valid? ::system-config config))

(defn explain-config
  "Explain what's wrong with system configuration"
  [config]
  (s/explain ::system-config config))
