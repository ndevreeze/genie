#! /usr/bin/env genie.clj

(ns test-divide-by-0
  "test exception handling."
  (:require
   [ndevreeze.cmdline :as cl]))

(def cli-options
  "Cmdlien options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn divide-by-0
  "Divide a number by 0, to generate exception"
  []
  (println "5 / 0 = " (/ 5 0)))

(defn script
  "Main script called by both main and -main"
  [_opt _arguments ctx]
  (println "ctx: " ctx)
  (divide-by-0))

(defn main
  "Main for genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-divide-by-0'
(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
