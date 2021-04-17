#! /usr/bin/env genie.clj

(genied.client/load-relative-file "test_load_file_lib.clj")

(ns test-load-file
  "We can use load-file here, but it needs an absolute path. Even ~
  (home-dir) is not understood. Also clojure.main/load-script does not
  seem to work here, as the classpath does not contain the currently
  loaded script.  Using load-relative-file in genied.client is an
  alternative. Currently this namespace is not aliased by
  default (would need to do it in the context of load-file)"
  (:require
   [ndevreeze.cmdline :as cl]
   [test-load-file-lib :as lib]))

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn script
  "Default script"
  [opt _arguments ctx]
  (println "ctx: " ctx)
  (lib/data-csv opt ctx)
  ;; classpath only contains Maven/.m2 jars.
  #_(println "classpath here: " (cp/classpath)))

(defn  main
  "Main for genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-dyn-cl

(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
