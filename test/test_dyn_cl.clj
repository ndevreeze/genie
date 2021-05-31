#! /usr/bin/env genie.clj

(ns test-dyn-cl
  "specific script for testing the classloader on the client side."
  (:require
   [ndevreeze.cmdline :as cl]
   [clojure.data.csv :as csv]))

(def cli-options
  "Cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn data-csv
  "Parse some csv in a string"
  []
  (println "Parsing csv using data.csv: "
           (csv/read-csv "abc,123,\"with,comma\"")))

(defn script
  "Main script called by both `main` and `-main`"
  [_opt _arguments ctx]
  (println "ctx: " ctx)
  (data-csv))

;; expect context/ctx now as first parameter, a map.
(defn main
  "Main from genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-dyn-cl'
(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
