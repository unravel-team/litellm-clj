(ns litellm.providers.kimi
  "Kimi/Moonshot provider implementation for LiteLLM."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as http]
            [litellm.errors :as errors]
            [litellm.providers.openai-compatible :as compat]))

(def default-api-base "https://api.moonshot.ai/v1")

(def default-cost-map
  "Default cost per token for Kimi/Moonshot models (in USD)."
  {"kimi-k2.7-code" {:input 0.00000095 :output 0.000004 :cache-read 0.00000019}
   "kimi-k2.7-code-highspeed" {:input 0.00000190 :output 0.000008 :cache-read 0.00000038}
   "kimi-k2.6" {:input 0.00000095 :output 0.000004 :cache-read 0.00000016}
   "kimi-k2.5" {:input 0.00000060 :output 0.000003 :cache-read 0.00000010}
   "moonshot-v1-8k" {:input 0.00000020 :output 0.000002}
   "moonshot-v1-32k" {:input 0.00000100 :output 0.000003}
   "moonshot-v1-128k" {:input 0.00000200 :output 0.000005}})

(defn- api-base
  [config]
  (str/replace (or (:api-base config) default-api-base) #"/+$" ""))

(defn chat-url
  "Return the Kimi chat completions URL for config."
  [config]
  (str (api-base config) "/chat/completions"))

(defn models-url
  "Return the Kimi models URL for config."
  [config]
  (str (api-base config) "/models"))

(defn transform-messages
  "Transform messages to Kimi's OpenAI-compatible wire format."
  [messages]
  (compat/transform-messages messages))

(defn transform-tools
  "Transform tools to Kimi's OpenAI-compatible wire format."
  [tools]
  (compat/transform-tools tools))

(defn- k2-7-code-model?
  [model]
  (str/starts-with? (str model) "kimi-k2.7-code"))

(defn- invalid-thinking!
  [message request details]
  (throw (errors/invalid-request
          message
          :request request
          :errors details)))

(defn- transform-thinking
  [request]
  (when-let [thinking (:thinking request)]
    (let [transformed (compat/transform-api-value thinking)
          type (:type transformed)
          keep (:keep transformed)]
      (when (and type (not (#{"enabled" "disabled"} type)))
        (invalid-thinking! "Kimi thinking.type must be enabled or disabled"
                           request
                           {:supported-values ["enabled" "disabled"]}))
      (when-not (or (nil? keep) (= "all" keep))
        (invalid-thinking! "Kimi thinking.keep only supports all"
                           request
                           {:supported-values ["all"]}))
      (when (and (k2-7-code-model? (:model request)) (= "disabled" type))
        (invalid-thinking! "Kimi K2.7 Code thinking cannot be disabled"
                           request
                           {:supported-values ["enabled"]}))
      transformed)))

(def ^:private optional-field-mappings
  [[:max-tokens :max_completion_tokens identity]
   [:temperature :temperature identity]
   [:top-p :top_p identity]
   [:frequency-penalty :frequency_penalty identity]
   [:presence-penalty :presence_penalty identity]
   [:stop :stop identity]
   [:stream :stream identity]
   [:tools :tools transform-tools]
   [:tool-choice :tool_choice compat/transform-tool-choice]
   [:response-format :response_format compat/transform-api-value]
   [:stream-options :stream_options compat/transform-api-value]
   [:prompt-cache-key :prompt_cache_key identity]
   [:safety-identifier :safety_identifier identity]])

(defn- assoc-present
  [m request request-key api-key f]
  (if (contains? request request-key)
    (assoc m api-key (f (get request request-key)))
    m))

(defn- optional-fields
  [request]
  (cond-> (reduce (fn [fields [request-key api-key f]]
                    (assoc-present fields request request-key api-key f))
                  {}
                  optional-field-mappings)
    (contains? request :thinking) (assoc :thinking (transform-thinking request))))

(defn transform-request-impl
  "Kimi-specific transform-request implementation."
  [_provider-name request _config]
  (compat/merge-extra-body
   {:model (:model request)
    :messages (transform-messages (:messages request))}
   (optional-fields request)
   (:extra-body request)))

(defn make-request-impl
  "Make a non-streaming Kimi chat completion request."
  [provider-name transformed-request thread-pool _telemetry config]
  (compat/post-json-async provider-name (chat-url config) transformed-request thread-pool config))

(defn make-streaming-request-impl
  "Make a streaming Kimi chat completion request."
  [provider-name transformed-request thread-pool config]
  (compat/post-sse provider-name
                   (chat-url config)
                   (assoc transformed-request :stream true)
                   thread-pool
                   config))

(defn transform-response-impl
  "Transform a Kimi response to the standard litellm-clj shape."
  [_provider-name response]
  (compat/transform-response response))

(defn transform-streaming-chunk-impl
  "Transform a Kimi streaming chunk to the standard litellm-clj shape."
  [_provider-name chunk]
  (compat/transform-streaming-chunk chunk))

(defn supports-streaming-impl
  "Kimi supports streaming chat completions."
  [_provider-name]
  true)

(defn supports-function-calling-impl
  "Kimi supports OpenAI-style function tools."
  [_provider-name]
  true)

(defn get-rate-limits-impl
  "Return conservative default Kimi rate limits."
  [_provider-name]
  {:requests-per-minute 1000
   :tokens-per-minute 50000})

(defn health-check-impl
  "Perform a Kimi models endpoint health check."
  [_provider-name thread-pool config]
  (try
    (let [request (cond-> {:headers (compat/default-headers config)
                           :timeout 5000
                           :as :json}
                    thread-pool (assoc :executor thread-pool))
          response (http/get (models-url config) request)]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "Kimi health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "Get Kimi cost per token for model."
  [_provider-name model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

(defn list-models
  "List available Kimi model ids."
  [config]
  (try
    (let [response (http/get (models-url config)
                             {:headers (compat/default-headers config)
                              :timeout (:timeout config 30000)
                              :as :json})]
      (if (= 200 (:status response))
        (mapv :id (get-in response [:body :data]))
        []))
    (catch Exception e
      (log/error "Error listing Kimi models" {:error (.getMessage e)})
      [])))
