#! /usr/bin/env genie.clj

(ns test-stdin
  "test stdin, also with redirection:
  * read as contents from file: cat file | genie test_stdin.clj
  * read as output from another process: ls | genie test_stdin.clj
  * read as a stream from another process: long-proc | grenie test_stdin.clj"
  (:require [ndevreeze.cmdline :as cl]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn read-standard-input
  "Read standard input and output each line in capitals."
  [_opt _ctx _arguments]
  (println "Start reading stdin")
  (doseq [line (line-seq (io/reader *in*))]
    (println (str/upper-case line)))
  (println "Finished reading stdin"))

(defn script
  "Script called by both `main` and `-main`"
  [opt arguments ctx]
  (read-standard-input opt ctx arguments))

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
