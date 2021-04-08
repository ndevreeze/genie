#! /usr/bin/env genie

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
  [opt ctx]
  (println "Parsing csv using data.csv: "
           (csv/read-csv "abc,123,\"with,comma\"")))

(defn script
  "Main script called by both `main` and `-main`"
  [opt arguments ctx]
  (println "ctx: " ctx)
  (data-csv opt ctx))

;; expect context/ctx now as first parameter, a map.
(defn main
  "Main from genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-dyn-cl

;; TODO - maybe need solution if script is called from another
;;        directory, in which case deps.edn cannot be found, which is
;;        used for bootstrapping.  or do use the -M option for giving
;;        the script path?
(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
