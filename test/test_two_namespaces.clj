#! /usr/bin/env genie

(ns test-lib-ns
  "Test with 2 namespaces - lib"
  (:require
   [clojure.data.csv :as csv]))

(defn data-csv
  "Slight variation, also parsing csv"
  [opt ctx]
  (println "Parsing csv using data.csv: " (csv/read-csv "two,namespaces")))

(ns test-main-ns
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
  [opt arguments ctx]
  (println "ctx: " ctx)
  (lib/data-csv opt ctx))

;; expect context/ctx now as first parameter, a map.
(defn main
  "Main for use by genie"
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
