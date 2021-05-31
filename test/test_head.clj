#! /usr/bin/env genie.clj

(ns test-head
  "test printing the first few lines of a given file including test for
  relative files."
  (:require [clojure.java.io :as io]
            [ndevreeze.cmdline :as cl]
            [clojure.string :as str]))

(def cli-options
  "Cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn head-file
  "Return the first 5 lines of `path`"
  [path]
  (if path
    (with-open [rdr (io/reader path)]
      (str/join "\n" (take 5 (line-seq rdr))))
    (println "Need file cmdline argument")))

(defn script
  "Main script called by both `main` and `-main`"
  [_opt arguments _ctx]
  (println (head-file (first arguments))))

(defn main
  "Main from genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-head'
(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
