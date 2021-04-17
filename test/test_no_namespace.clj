#! /usr/bin/env genie.clj

;; 2021-03-03: specific script for testing the classloader on the
;; client side.  this version without a namespace declaration. This
;; works in genie, but not in clj.

(require '[ndevreeze.cmdline :as cl])
(require '[clojure.data.csv :as csv])

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn data-csv
  "Calling csv/read-csv"
  []
  (println "Parsing csv using data.csv: " (csv/read-csv "test,no,namespace")))

(defn script
  "Default script"
  [_opt _arguments ctx]
  (println "ctx: " ctx)
  (data-csv))

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
