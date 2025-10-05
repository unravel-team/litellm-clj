(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'tech.unravel/litellm-clj)
(def version "0.2.0")
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
                :scm {:url "https://github.com/unravel-team/clj-litellm"
                      :connection "scm:git:git://github.com/unravel-team/clj-litellm.git"
                      :developerConnection "scm:git:ssh://git@github.com/unravel-team/clj-litellm.git"
                      :tag (str "v" version)}
                :pom-data [[:description "A Clojure port of LiteLLM providing a unified interface for multiple LLM providers"]
                           [:url "https://github.com/unravel-team/clj-litellm"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/licenses/MIT"]]]
                           [:developers
                            [:developer
                             [:name "Unravel Team"]]]]})
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
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
