#! /usr/bin/env genie

(ns test-loggers
  "test if loggers at daemon, client and script don't get mingled, log
  to the right log file.  just log on info level here, logging on
  debug level is tested elsewhere."
  (:require [ndevreeze.cmdline :as cl]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]))

(def cli-options
  "Cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-m" "--message MESSAGE" "Message to log"]
   ["-h" "--help" "Show this help"]])

(defn logging-cwd
  "Some logging to cwd"
  [opt ctx arguments]
  (println "Test logging to current-dir")
  (log/init {:location :cwd :name "test-loggers" :cwd (:cwd ctx)})
  (log/info "msg: " (:message opt)))

(defn script
  "Main script called by `main` and `-main`"
  [opt arguments ctx]
  (logging-cwd opt ctx arguments))

;; expect context/ctx now as first parameter, a map.
(defn main
  "Main from genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test'
(defn -main
  "Entry point from clj cmdline script.
   Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
