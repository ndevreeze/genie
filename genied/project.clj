(defproject ndevreeze/genied "0.1.0-SNAPSHOT"
  :description "Clojure script server/daemon - genied"
  :url "https://github.com/ndevreeze/genie"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.12.1"]
                 [org.clojure/tools.cli "1.1.230"]
                 [commons-io "2.20.0"]                           ; 2024-08-17: for Excel, POI 5.3.0. Make explicit, lein ancient will mention new versions.
                 [org.apache.commons/commons-compress "1.27.1"]  ; 2024-08-17: this one used commons-io 2.16.1, make explicit
                 [clj-commons/fs "1.6.311"]
                 [nrepl "1.3.1"]
                 [clj-commons/pomegranate "1.2.24"]              ; 2021-02-21: for dynamic loading of libraries.
                 [org.apache.httpcomponents/httpclient "4.5.14"] ; explicit, also 4.5.8 in deps.
                 [org.apache.httpcomponents/httpcore "4.4.16"]   ; 2021-05-18: also explicit for now, wrt conflicts.
                 [org.slf4j/slf4j-nop "2.0.17"]                  ; 2021-04-04: getting rid of SLF warning
                 [org.jsoup/jsoup "1.21.1"]                      ; 2021-04-04: try to get rid of reflective warning.
                 [ndevreeze/logger "0.6.2"]                      ; 2024-04-03: 0.6.2 includes java-time 1.4.2 and threeten-extra 1.2.
                 [ndevreeze/cmdline "0.2.0"]
                 [org.tcrawley/dynapath "1.1.0"]                 ; 2023-01-29: fix lein deps warning
                 [commons-codec "1.19.0"]                        ; 2023-01-29: fix lein deps warning

                 ;; 2024-04-03: remove here, should be loaded from logger library.
                 ;; [clojure.java-time/clojure.java-time "1.4.2"]   ; 2024-04-03: wrt time/interval in missed-sales.
                 ;; [org.threeten/threeten-extra "1.2"]             ; 2024-04-03: prb this needs to be close to java-time


                 ]
  :main ^:skip-aot genied.core
  :jvm-opts ["--illegal-access=debug"] ;; 2021-04-04: for lein uberjar, but no more info.
  :target-path "target/%s"
  ;; 2021-04-05: reflection warnings on Pomegranate and nrepl, so disable for now.
  :global-vars {*warn-on-reflection* false}

  ;; 2021-04-21: from check-namespace-decls, do not use prefixes, is not standard.
  :check-namespace-decls {:prefix-rewriting false}

  :codox {:output-path "../docs/api"
          :doc-paths ["../docs"]
          :metadata {:doc/format :markdown}
          :source-uri "https://github.com/ndevreeze/genie/blob/main/genied/{filepath}#L{line}"}

  :profiles {:dev {:dependencies [[midje "1.10.10"]]}
             :uberjar {:aot :all}})
