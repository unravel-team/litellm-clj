(ns litellm.streaming
  "Core streaming utilities for LiteLLM"
  (:require [clojure.core.async :as async :refer [chan close!]]
            [clojure.tools.logging :as log]))

;; ============================================================================
;; Channel Management
;; ============================================================================

(defn create-stream-channel
  "Create a buffered channel for streaming responses.
  
  Options:
  - :buffer-size - Size of the channel buffer (default: 64)"
  [& {:keys [buffer-size] :or {buffer-size 64}}]
  (chan buffer-size))

(defn close-stream!
  "Safely close a stream channel."
  [ch]
  (when ch
    (try
      (close! ch)
      (catch Exception e
        (log/warn "Error closing stream" {:error (.getMessage e)})))))

;; ============================================================================
;; Error Handling
;; ============================================================================

(defn stream-error
  "Create an error chunk for streaming.
  
  Error chunks are special messages placed on the channel to signal errors."
  [provider message & {:keys [status code data]}]
  (cond-> {:type :error
           :error-type :provider-error
           :provider provider
           :message message}
    status (assoc :status status)
    code (assoc :code code)
    data (assoc :data data)))

(defn is-error-chunk?
  "Check if a chunk is an error chunk."
  [chunk]
  (= :error (:type chunk)))

;; ============================================================================
;; Content Extraction
;; ============================================================================

(defn extract-content
  "Extract content from a streaming chunk.
  
  Returns the incremental content string, or nil if no content present."
  [chunk]
  (when-not (is-error-chunk? chunk)
    (get-in chunk [:choices 0 :delta :content])))

;; ============================================================================
;; SSE (Server-Sent Events) Parsing
;; ============================================================================

(defn parse-sse-line
  "Parse a Server-Sent Events line.
  
  SSE format:
  data: {json}
  data: [DONE]
  
  Returns parsed JSON map or nil."
  [line json-decoder]
  (when (clojure.string/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when-not (= data "[DONE]")
        (try
          (json-decoder data true)
          (catch Exception e
            (log/debug "Failed to parse SSE line" {:line line :error (.getMessage e)})
            nil))))))
