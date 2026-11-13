(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'org.clojars.pooriayousefi/mcp.clj)
(def version "1.0.2")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src"]
                :target-dir class-dir})
  (b/write-pom
   {:class-dir class-dir
    :lib lib
    :version version
    :basis @basis
    :src-dirs ["src"]
    ;; Ignore any local/root pom.xml so pom-data is always applied.
    :src-pom :none
    :scm {:url "https://github.com/pooriayousefi/mcp.clj"
          :connection "scm:git:https://github.com/pooriayousefi/mcp.clj.git"
          :tag "v1.0.2"}
    :pom-data
    [[:description "Pure Clojure SDK for the Model Context Protocol (MCP)."]
     [:url "https://github.com/pooriayousefi/mcp.clj"]
     [:licenses
      [:license
       [:name "Eclipse Public License 1.0"]
       [:url "https://www.eclipse.org/legal/epl-v10.html"]
       [:distribution "repo"]]]]})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib
                                     :class-dir class-dir})
              :sign-releases? false}))
