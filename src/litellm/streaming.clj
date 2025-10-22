(ns litellm.streaming
  "Core streaming utilities for LiteLLM"
  (:require [clojure.core.async :as async :refer [chan close! go-loop <! >!]]
            [clojure.string :as str]
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

(defn extract-finish-reason
  "Extract finish reason from a streaming chunk."
  [chunk]
  (get-in chunk [:choices 0 :finish-reason]))

;; ============================================================================
;; Stream Accumulation
;; ============================================================================

(defn collect-stream
  "Blocking function to collect all chunks from a stream into a final string.
  
  Returns a map with:
  - :content - The complete accumulated content
  - :chunks - Vector of all chunks received
  - :error - Error map if an error occurred
  
  Example:
    (let [ch (completion :model \"...\" :stream true)
          result (collect-stream ch)]
      (if (:error result)
        (println \"Error:\" (:error result))
        (println \"Content:\" (:content result))))"
  [source-ch]
  (let [result (atom {:content ""
                      :chunks []
                      :error nil})]
    (loop []
      (when-let [chunk (async/<!! source-ch)]
        (swap! result update :chunks conj chunk)
        (if (is-error-chunk? chunk)
          (swap! result assoc :error chunk)
          (when-let [content (extract-content chunk)]
            (swap! result update :content str content)))
        (recur)))
    @result))

(defn accumulate-stream
  "Accumulate streaming chunks into complete strings.
  
  Takes a source channel of chunks and returns a new channel that emits
  accumulated content strings as they grow.
  
  Example:
    (let [source-ch (completion :model \"...\" :stream true)
          acc-ch (accumulate-stream source-ch)]
      (go-loop []
        (when-let [accumulated (<! acc-ch)]
          (println \"So far:\" accumulated)
          (recur))))"
  [source-ch]
  (let [output-ch (chan 64)]
    (go-loop [accumulated ""]
      (if-let [chunk (<! source-ch)]
        (if (is-error-chunk? chunk)
          (do
            (>! output-ch chunk)
            (close-stream! output-ch))
          (let [content (extract-content chunk)
                new-accumulated (str accumulated content)]
            (when content
              (>! output-ch new-accumulated))
            (recur new-accumulated)))
        (close-stream! output-ch)))
    output-ch))

;; ============================================================================
;; Callback-based Streaming
;; ============================================================================

(defn consume-stream-with-callbacks
  "Consume a stream channel with callback functions.
  
  This provides a callback-based interface over core.async channels.
  
  Parameters:
  - ch: Source channel of chunks
  - on-chunk: Called for each chunk (fn [chunk])
  - on-complete: Called when stream ends with final accumulated response (fn [response])
  - on-error: Called if an error occurs (fn [error-chunk])
  
  Returns nil immediately. All interaction happens through callbacks.
  
  Example:
    (let [ch (completion :model \"...\" :stream true)]
      (consume-stream-with-callbacks ch
        (fn [chunk] (print (extract-content chunk)))
        (fn [response] (println \"\\nDone!\" response))
        (fn [error] (println \"Error:\" error))))"
  [ch on-chunk on-complete on-error]
  (go-loop [chunks []
            accumulated-content ""]
    (if-let [chunk (<! ch)]
      (if (is-error-chunk? chunk)
        (do
          (when on-error
            (on-error chunk))
          (close-stream! ch))
        (do
          (when on-chunk
            (on-chunk chunk))
          (let [content (extract-content chunk)
                new-content (str accumulated-content content)]
            (recur (conj chunks chunk) new-content))))
      ;; Channel closed, stream complete
      (when on-complete
        (let [last-chunk (last chunks)
              final-response {:id (get last-chunk :id)
                             :object "chat.completion"
                             :created (get last-chunk :created)
                             :model (get last-chunk :model)
                             :choices [{:index 0
                                       :message {:role :assistant
                                                :content accumulated-content}
                                       :finish-reason (extract-finish-reason last-chunk)}]
                             :usage nil}]
          (on-complete final-response))))))

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
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when-not (= data "[DONE]")
        (try
          (json-decoder data true)
          (catch Exception e
            (log/debug "Failed to parse SSE line" {:line line :error (.getMessage e)})
            nil))))))
