#! /usr/bin/env genie.clj

(ns test-write-file
  "Test writing a file as a long process than can be interrupted. If the
  genie client is stopped, the script should stop as well."
  (:require [ndevreeze.cmdline :as cl]
            [me.raynes.fs :as fs]))

(def cli-options
  "Cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]
   ["-f" "--file FILE" "Path of file to write to"]
   ["-n" "--lines NLINES" "Number of lines to write"
    :default 5
    :parse-fn #(Integer/parseInt %)]
   ["-d" "--delay DELAY" "Delay in msec after writing each line"
    :default 1000
    :parse-fn #(Integer/parseInt %)]])

(defn write-file
  "Write a file according to options given"
  [{:keys [file lines delay]}]
  (fs/delete file)
  (doseq [i (range lines)]
    (spit file (str "Line: " i "\n") :append true)
    (Thread/sleep delay)))

(defn script
  "Main script called from `main` and `-main`"
  [opt _arguments _ctx]
  (if (:file opt)
    (write-file opt)
    (println "Need --file parameter")))

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
