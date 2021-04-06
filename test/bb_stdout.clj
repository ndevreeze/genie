#! /usr/bin/env bb

;; helper test for testing stdin/stdout functionality of both babashka en genie/nrepl.

(ns bb-stdout
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]))

(def cli-options
  [["-n" "--lines LINES" "Number of lines to output"
    :default 5
    :parse-fn #(Integer/parseInt %)]
   ["-d" "--delay DELAY" "Number of msec to sleep between lines"
    :default 1000
    :parse-fn #(Integer/parseInt %)]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(def log-time-pattern (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSSZ"))

(defn current-timestamp
  "Return current timestamp in a format suitable for a filename.
   In current timezone"
  []
  (.format (java.time.ZonedDateTime/now) log-time-pattern))

(defn output-line
  "Output a line with the current timestamp and a counter"
  [counter]
  (let [msg (format "[%s] counter: %5d\n" (current-timestamp) counter)]
    (print msg)
    (flush)))

(defn output-lines
  [opt]
  (doseq [counter (range (:lines opt))]
    (output-line counter)
    (Thread/sleep (:delay opt))))

(defn main
  "Main function"
  []
  (let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)
        opt (:options opts)
        args (:arguments opts)]
    (output-lines opt)))

(main)
