#! /usr/bin/env genie

(ns test-log-concurrent
  "Test if loggers at daemon, client and script don't get mingled, log
  to the right log file.  log several lines, each with one second
  interval. So we can test if we run this script multiple times at the
  same time, the logs do not get mixed up."
  (:require [ndevreeze.cmdline :as cl]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]))

(def cli-options
  "Cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-m" "--message MESSAGE" "Message to log"]
   ["-n" "--number NUMBER" "Number of lines to log"
    :default 2 :parse-fn #(Integer/parseInt %)]
   ["-h" "--help" "Show this help"]])

(defn logging-cwd
  "Test some logging to current working directory (cwd)"
  [opt ctx arguments]
  (println "Test logging to current-dir")
  (log/init {:location :cwd :name "test-loggers" :cwd (:cwd ctx)})
  (doseq [i (range (:number opt))]
    (log/info (format "%03d: %s" i (:message opt)))
    (Thread/sleep 1000)))

(defn script
  "Main script called by both `main` and `-main`"
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
