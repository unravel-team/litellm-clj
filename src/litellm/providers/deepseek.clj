(ns litellm.providers.deepseek
  "DeepSeek provider implementation for LiteLLM."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as http]
            [litellm.providers.openai-compatible :as compat]))

(def default-api-base "https://api.deepseek.com")

(def default-cost-map
  "Default cost per token for DeepSeek models (in USD)."
  {"deepseek-v4-flash" {:input 0.00000014 :output 0.00000028 :cache-read 0.0000000028}
   "deepseek-v4-pro" {:input 0.000000435 :output 0.00000087 :cache-read 0.000000003625}
   "deepseek-chat" {:input 0.00000014 :output 0.00000028 :cache-read 0.0000000028}
   "deepseek-reasoner" {:input 0.00000014 :output 0.00000028 :cache-read 0.0000000028}})

(defn- api-base
  [config]
  (str/replace (or (:api-base config) default-api-base) #"/+$" ""))

(defn chat-url
  "Return the DeepSeek chat completions URL for config."
  [config]
  (str (api-base config) "/chat/completions"))

(defn models-url
  "Return the DeepSeek models URL for config."
  [config]
  (str (api-base config) "/models"))

(defn transform-messages
  "Transform messages to DeepSeek's OpenAI-compatible wire format."
  [messages]
  (compat/transform-messages messages))

(defn transform-tools
  "Transform tools to DeepSeek's OpenAI-compatible wire format."
  [tools]
  (compat/transform-tools tools))

(defn- reasoning-effort-keyword
  [effort]
  (when effort
    (keyword (compat/kw->api-string effort))))

(def ^:private reasoning-effort-settings
  {:low {:thinking {:type "enabled"} :reasoning_effort "high"}
   :medium {:thinking {:type "enabled"} :reasoning_effort "high"}
   :high {:thinking {:type "enabled"} :reasoning_effort "high"}
   :xhigh {:thinking {:type "enabled"} :reasoning_effort "max"}
   :max {:thinking {:type "enabled"} :reasoning_effort "max"}
   :minimal {:thinking {:type "disabled"}}
   :none {:thinking {:type "disabled"}}})

(def ^:private optional-field-mappings
  [[:max-tokens :max_tokens identity]
   [:temperature :temperature identity]
   [:top-p :top_p identity]
   [:frequency-penalty :frequency_penalty identity]
   [:presence-penalty :presence_penalty identity]
   [:stop :stop identity]
   [:stream :stream identity]
   [:tools :tools transform-tools]
   [:tool-choice :tool_choice compat/transform-tool-choice]
   [:thinking :thinking compat/transform-api-value]
   [:response-format :response_format compat/transform-api-value]
   [:stream-options :stream_options compat/transform-api-value]])

(defn- assoc-present
  [m request request-key api-key f]
  (if (contains? request request-key)
    (assoc m api-key (f (get request request-key)))
    m))

(defn- optional-fields
  [request]
  (let [reasoning-settings (get reasoning-effort-settings
                                (reasoning-effort-keyword (:reasoning-effort request)))
        derived-thinking (when-not (contains? request :thinking)
                           (:thinking reasoning-settings))]
    (cond-> (reduce (fn [fields [request-key api-key f]]
                      (assoc-present fields request request-key api-key f))
                    {}
                    optional-field-mappings)
      derived-thinking (assoc :thinking derived-thinking)
      (:reasoning_effort reasoning-settings) (assoc :reasoning_effort (:reasoning_effort reasoning-settings)))))

(defn transform-request-impl
  "DeepSeek-specific transform-request implementation."
  [_provider-name request _config]
  (compat/merge-extra-body
   {:model (:model request)
    :messages (transform-messages (:messages request))}
   (optional-fields request)
   (:extra-body request)))

(defn make-request-impl
  "Make a non-streaming DeepSeek chat completion request."
  [provider-name transformed-request thread-pool _telemetry config]
  (compat/post-json-async provider-name (chat-url config) transformed-request thread-pool config))

(defn make-streaming-request-impl
  "Make a streaming DeepSeek chat completion request."
  [provider-name transformed-request thread-pool config]
  (compat/post-sse provider-name
                   (chat-url config)
                   (assoc transformed-request :stream true)
                   thread-pool
                   config
                   compat/transform-streaming-chunk))

(defn transform-response-impl
  "Transform a DeepSeek response to the standard litellm-clj shape."
  [_provider-name response]
  (compat/transform-response response))

(defn transform-streaming-chunk-impl
  "Transform a DeepSeek streaming chunk to the standard litellm-clj shape."
  [_provider-name chunk]
  (compat/transform-streaming-chunk chunk))

(defn supports-streaming-impl
  "DeepSeek supports streaming chat completions."
  [_provider-name]
  true)

(defn supports-function-calling-impl
  "DeepSeek supports OpenAI-style function tools."
  [_provider-name]
  true)

(defn get-rate-limits-impl
  "Return conservative default DeepSeek rate limits."
  [_provider-name]
  {:requests-per-minute 1000
   :tokens-per-minute 50000})

(defn health-check-impl
  "Perform a DeepSeek models endpoint health check."
  [_provider-name thread-pool config]
  (try
    (let [request (cond-> {:headers (compat/default-headers config)
                           :timeout 5000
                           :as :json}
                    thread-pool (assoc :executor thread-pool))
          response (http/get (models-url config) request)]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "DeepSeek health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "Get DeepSeek cost per token for model."
  [_provider-name model]
  (get default-cost-map model {:input 0.0 :output 0.0}))

(defn list-models
  "List available DeepSeek model ids."
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
      (log/error "Error listing DeepSeek models" {:error (.getMessage e)})
      [])))
