#! /usr/bin/env genie.clj

(ns test-stdout-stderr
  "test stdout en stderr redirection:
  * script writes to dynamic vars *out* and *err*
  * nrepl takes care of the bindings, so it will 'catch' these writes.
  * nrepl will then pass the contents to the client in the fields :out and :err
  * the (babashka/tcl) client will then read these fields and write the
   contents on its dynamic vars *out* and *err* (or directly to stdout/stderr
   in Tcl)
  * Babashka takes care that *out* and *err* are bound to the
  * stdout/err of the client process."
  (:require [ndevreeze.cmdline :as cl]))

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn standard-error
  "Print something to stdout and stderr"
  []
  (println "Next line to stderr (this line on stdout):")
  (binding [*out* *err*]
    (println "Hello, STDERR!"))
  (println "Stdout again"))

(defn script
  "Script called from `main` and `-main`"
  [_opt _arguments _ctx]
  (standard-error))

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
