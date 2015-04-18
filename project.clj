(defproject funcool/futura "0.1.0"
  :description "A basic building blocks for async programming."
  :url "https://github.com/funcool/futura"
  :license {:name "BSD (2 Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[cats "0.4.0"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}

  :source-paths ["src"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]]
                   :codeina {:sources ["src"]
                             :language :clojure
                             :output-dir "doc/api"}
                   :plugins [[funcool/codeina "0.1.0-SNAPSHOT"
                              :exclusions [org.clojure/clojure]]]}})
