#! /usr/bin/env genie

;; test printing the first few lines of a given file
;; including test for relative files.

(ns test-head
  (:require
   [ndevreeze.cmdline :as cl]
   [clojure.string :as str]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn head-file
  "Return the first 5 lines of `path`"
  [path]
  (with-open [rdr (clojure.java.io/reader path)]
    (str/join "\n" (take 5 (line-seq rdr)))))

(defn script [opt arguments ctx]
  ;;  (println "ctx: " ctx)
  (println (head-file (first arguments))))

(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-divide-by-0

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
