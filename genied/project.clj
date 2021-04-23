(defproject ndevreeze/genied "0.1.0-SNAPSHOT"
  :description "Clojure script server/daemon - genied"
  :url "https://github.com/ndevreeze/genie"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/tools.cli "1.0.206"]
                 [clj-commons/fs "1.6.307"]
                 [nrepl "0.8.3"]
                 [clj-commons/pomegranate "1.2.1"] ;; 2021-02-21: for dynamic loading of libraries.
                 [org.apache.httpcomponents/httpclient "4.5.9"] ;; explicit, also 4.5.8 in deps.
                 [org.slf4j/slf4j-nop "1.7.30"] ;; 2021-04-04: getting rid of SLF warning
                 [org.jsoup/jsoup "1.13.1"] ;; 2021-04-04: try to get rid of reflective warning.
                 [ndevreeze/logger "0.4.0"]
                 [ndevreeze/cmdline "0.2.0"]]
  :main ^:skip-aot genied.core
  :jvm-opts ["--illegal-access=debug"] ;; 2021-04-04: for lein uberjar, but no more info.
  :target-path "target/%s"
  ;; 2021-04-05: reflection warnings on Pomegranate and nrepl, so disable for now.
  :global-vars {*warn-on-reflection* false}

  ;; 2021-04-21: from check-namespace-decls, do not use prefixes, is not standard.
  :check-namespace-decls {:prefix-rewriting false}

  :codox {:output-path "codox"
          :doc-paths ["../docs"]
          :metadata {:doc/format :markdown}
          :source-uri "https://github.com/ndevreeze/genie/blob/main/genied/{filepath}#L{line}"}

  :profiles {:dev {:dependencies [[midje "1.9.10"]]}
             :uberjar {:aot :all}})
