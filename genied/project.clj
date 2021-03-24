(defproject genied "0.1.0-SNAPSHOT"
  :description "Clojure script server/daemon - genied"
  :url "https://github.com/ndevreeze/genie"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [
                 ;; [org.apache.commons/commons-compress "1.19"] ;; explicitly, raynes/fs uses an old one.
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.cli "1.0.194"]
                 [clj-commons/fs "1.6.307"]
                 [nrepl "0.8.3"]
                 [clj-commons/pomegranate "1.2.0"] ;; 2021-02-21: for dynamic loading of libraries.
                 [ndevreeze/logger "0.2.0"]
                 [ndevreeze/cmdline "0.1.2"]]
  :main ^:skip-aot genied.core
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[midje "1.9.9"]]}
             :uberjar {:aot :all}})

