#! /usr/bin/env genie.clj

(ns test-lib-ns
  "Test with 2 namespaces - lib"
  (:require
   [clojure.data.csv :as csv]))

(defn data-csv
  "Slight variation, also parsing csv"
  [_opt _ctx]
  (println "Parsing csv using data.csv: " (csv/read-csv "two,namespaces")))

(ns test-two-namespaces
  "Test with 2 namespaces - main"
  (:require
   [ndevreeze.cmdline :as cl]
   [test-lib-ns :as lib]))

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn script
  "Default script"
  [opt _arguments ctx]
  (println "ctx: " ctx)
  (lib/data-csv opt ctx))

(defn main
  "Main for use by genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
