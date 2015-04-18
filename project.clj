(defproject funcool/futura "0.1.0-SNAPSHOT"
  :description "A basic building blocks for async programming."
  :url "https://github.com/funcool/futura"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[cats "0.4.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.reactivestreams/reactive-streams "1.0.0.RC5"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}
  :source-paths ["src"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :javac-options ["-target" "1.8" "-source" "1.8" "-Xlint:-options"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.7.0-beta1"]]
                   :codeina {:sources ["src"]
                             :language :clojure
                             :output-dir "doc/api"}
                   :plugins [[funcool/codeina "0.1.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]]}})
