(ns build
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def lib 'tech.unravel/litellm-clj)
(def repository-url "https://github.com/unravel-team/litellm-clj")
(def source-revision (b/git-process {:git-args ["rev-parse" "HEAD"]}))
;; The version is maj.min.x: maj.min comes from the VERSION file (bump it
;; with `make release-major` / `make release-minor`), x is the number of git
;; commits at build time.
(def version
  (format "%s.%s" (str/trim (slurp "VERSION")) (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                :scm {:url repository-url
                      :connection (str "scm:git:" repository-url ".git")
                      :developerConnection (str "scm:git:" repository-url ".git")
                      :tag source-revision}
                :pom-data [[:description "A Clojure port of LiteLLM providing a unified interface for multiple LLM providers"]
                           [:url repository-url]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/licenses/MIT"]]]
                           [:developers
                            [:developer
                             [:name "Unravel Team"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (let [metadata-file (io/file class-dir "META-INF/litellm-clj/build-info.edn")]
    (io/make-parents metadata-file)
    (spit metadata-file
          (pr-str {:repository repository-url
                   :revision source-revision
                   :version version
                   :dirty? (boolean (seq (b/git-process {:git-args ["status" "--porcelain"]})))})))
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Built" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  (println "Installed" lib version))

(defn deploy [_]
  (jar nil)
  (println "Deploying to Clojars...")
  (println "Make sure you have CLOJARS_USERNAME and CLOJARS_PASSWORD set")
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact jar-file
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
