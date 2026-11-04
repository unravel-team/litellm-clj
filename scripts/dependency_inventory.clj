(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; The CLI basis is the actual resolved graph, not just the direct declarations.
(let [basis (edn/read-string (slurp (System/getProperty "clojure.basis")))
      output (first *command-line-args*)]
  (when-not output (throw (ex-info "Supply an inventory output path" {})))
  (io/make-parents output)
  (with-open [writer (io/writer output)]
    (.write writer "coordinate\tversion\tartifact\tsha256\tdependents\n")
    (doseq [[coordinate {:keys [mvn/version paths dependents]}] (sort-by (comp str key) (:libs basis))]
      (when-not (and version (re-matches #"[0-9][0-9A-Za-z.\-]*" version)
                     (not (re-find #"(?i)SNAPSHOT" version)))
        (throw (ex-info "Resolved dependency is not an exact Maven release" {:coordinate coordinate})))
      (doseq [path (sort paths)]
        (let [file (io/file path)
              digest (java.security.MessageDigest/getInstance "SHA-256")
              buffer (byte-array 8192)]
          (with-open [input (io/input-stream file)]
            (loop []
              (let [n (.read input buffer)]
                (when (pos? n)
                  (.update digest buffer 0 n)
                  (recur)))))
          (.write writer
                  (str/join "\t" [(str coordinate) version (.getName file)
                                    (format "%064x" (java.math.BigInteger. 1 (.digest digest)))
                                    (str/join "," (sort (map str dependents)))]))
          (.write writer "\n"))))))
