(ns litellm.providers.zai
  "Z.AI GLM provider implementation for LiteLLM."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [hato.client :as http]
            [litellm.errors :as errors]
            [litellm.providers.openai-compatible :as compat]))

(def default-api-base "https://api.z.ai/api/paas/v4")

(def default-cost-map
  "Default cost per token for Z.AI GLM models (in USD)."
  {"glm-5.2" {:input 0.0000014 :output 0.0000044 :cache-read 0.00000026}
   "glm-5.1" {:input 0.0000014 :output 0.0000044 :cache-read 0.00000026}
   "glm-5" {:input 0.0000010 :output 0.0000032 :cache-read 0.00000020}
   "glm-5-turbo" {:input 0.0000012 :output 0.0000040 :cache-read 0.00000024}
   "glm-4.7" {:input 0.0000006 :output 0.0000022 :cache-read 0.00000011}
   "glm-4.7-flashx" {:input 0.00000007 :output 0.0000004 :cache-read 0.00000001}
   "glm-4.7-flash" {:input 0.0 :output 0.0 :cache-read 0.0}
   "glm-4.6" {:input 0.0000006 :output 0.0000022 :cache-read 0.00000011}
   "glm-4.5" {:input 0.0000006 :output 0.0000022 :cache-read 0.00000011}
   "glm-4.5-x" {:input 0.0000022 :output 0.0000089 :cache-read 0.00000045}
   "glm-4.5-air" {:input 0.0000002 :output 0.0000011 :cache-read 0.00000003}
   "glm-4.5-airx" {:input 0.0000011 :output 0.0000045 :cache-read 0.00000022}
   "glm-4.5-flash" {:input 0.0 :output 0.0 :cache-read 0.0}
   "glm-4-32b-0414-128k" {:input 0.0000001 :output 0.0000001}})

(defn- api-base
  [config]
  (str/replace (or (:api-base config) default-api-base) #"/+$" ""))

(defn chat-url
  "Return the Z.AI chat completions URL for config."
  [config]
  (str (api-base config) "/chat/completions"))

(defn models-url
  "Return the Z.AI models URL for config."
  [config]
  (str (api-base config) "/models"))

(defn transform-messages
  "Transform messages to Z.AI's OpenAI-compatible wire format."
  [messages]
  (compat/transform-messages messages))

(defn transform-tools
  "Transform tools to Z.AI's OpenAI-compatible wire format."
  [tools]
  (compat/transform-tools tools))

(def ^:private optional-field-mappings
  [[:max-tokens :max_tokens identity]
   [:temperature :temperature identity]
   [:top-p :top_p identity]
   [:frequency-penalty :frequency_penalty identity]
   [:presence-penalty :presence_penalty identity]
   [:stop :stop identity]
   [:stream :stream identity]
   [:tools :tools transform-tools]
   [:thinking :thinking compat/transform-api-value]
   [:reasoning-effort :reasoning_effort compat/kw->api-string]
   [:response-format :response_format compat/transform-api-value]
   [:stream-options :stream_options compat/transform-api-value]
   [:do-sample :do_sample identity]
   [:tool-stream :tool_stream identity]])

(defn- assoc-present
  [m request request-key api-key f]
  (if (contains? request request-key)
    (assoc m api-key (f (get request request-key)))
    m))

(defn- top-level-clear-thinking
  [request]
  (when (and (contains? request :clear-thinking)
             (not (contains? (:thinking request) :clear-thinking)))
    {:clear_thinking (:clear-thinking request)}))

(defn- transform-tool-choice
  [tool-choice]
  (let [transformed (compat/transform-tool-choice tool-choice)]
    (if (or (nil? transformed) (= "auto" transformed))
      transformed
      (throw (errors/invalid-request
              "Z.AI only supports automatic tool choice"
              :request {:tool-choice tool-choice}
              :errors {:supported-values ["auto"]})))))

(defn- optional-fields
  [request]
  (let [clear-thinking (top-level-clear-thinking request)]
    (cond-> (reduce (fn [fields [request-key api-key f]]
                      (assoc-present fields request request-key api-key f))
                    {}
                    optional-field-mappings)
      clear-thinking (update :thinking merge clear-thinking)
      (contains? request :tool-choice) (assoc :tool_choice (transform-tool-choice (:tool-choice request))))))

(defn transform-request-impl
  "Z.AI-specific transform-request implementation."
  [_provider-name request _config]
  (compat/merge-extra-body
   {:model (:model request)
    :messages (transform-messages (:messages request))}
   (optional-fields request)
   (:extra-body request)))

(defn make-request-impl
  "Make a non-streaming Z.AI chat completion request."
  [provider-name transformed-request thread-pool _telemetry config]
  (compat/post-json-async provider-name (chat-url config) transformed-request thread-pool config))

(defn make-streaming-request-impl
  "Make a streaming Z.AI chat completion request."
  [provider-name transformed-request thread-pool config]
  (compat/post-sse provider-name
                   (chat-url config)
                   (assoc transformed-request :stream true)
                   thread-pool
                   config
                   compat/transform-streaming-chunk))

(defn transform-response-impl
  "Transform a Z.AI response to the standard litellm-clj shape."
  [_provider-name response]
  (compat/transform-response response))

(defn transform-streaming-chunk-impl
  "Transform a Z.AI streaming chunk to the standard litellm-clj shape."
  [_provider-name chunk]
  (compat/transform-streaming-chunk chunk))

(defn supports-streaming-impl
  "Z.AI supports streaming chat completions."
  [_provider-name]
  true)

(defn supports-function-calling-impl
  "Z.AI supports OpenAI-style function tools."
  [_provider-name]
  true)

(defn get-rate-limits-impl
  "Return conservative default Z.AI rate limits."
  [_provider-name]
  {:requests-per-minute 1000
   :tokens-per-minute 50000})

(defn health-check-impl
  "Perform a Z.AI models endpoint health check."
  [_provider-name thread-pool config]
  (try
    (let [request (cond-> {:headers (compat/default-headers config)
                           :timeout 5000
                           :as :json}
                    thread-pool (assoc :executor thread-pool))
          response (http/get (models-url config) request)]
      (= 200 (:status response)))
    (catch Exception e
      (log/warn "Z.AI health check failed" {:error (.getMessage e)})
      false)))

(defn get-cost-per-token-impl
  "Get Z.AI cost per token for model."
  [_provider-name model]
  (get default-cost-map model {:input 0.0 :output 0.0}))
