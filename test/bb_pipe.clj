#! /usr/bin/env bb

;; helper test for testing stdin/stdout functionality of both babashka
;; en genie/nrepl.  this ones pipes stdin to stdout with a given
;; delay.

;; $ ./bb-stdout.clj -n 5 -d 1000 | ./bb-pipe.clj -d 2000

(ns bb-pipe
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def cli-options
  [["-d" "--delay DELAY" "Number of msec to sleep between lines"
    :default 1000
    :parse-fn #(Integer/parseInt %)]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(def log-time-pattern
  "Long log timestamp pattern"
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSSZ"))

(defn current-timestamp
  "Return current timestamp in a format suitable for a filename.
   In current timezone"
  []
  (.format (java.time.ZonedDateTime/now) log-time-pattern))

(defn output-line
  "Output a line with the current timestamp and a counter"
  [line]
  (let [msg (format "[%s] line: %s\n" (current-timestamp) line)]
    (print msg)
    (flush)))

(defn output-lines
  "Output the lines received on stdin.
   Use `(:delay opt)` after each line"
  [opt]
  (doseq [line (line-seq (io/reader *in*))]
    (output-line line)
    (Thread/sleep (:delay opt))))

(defn main
  "Main function"
  []
  (let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)
        opt (:options opts)
        args (:arguments opts)]
    (output-lines opt)))

(main)
