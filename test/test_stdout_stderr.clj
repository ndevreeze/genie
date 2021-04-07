#! /usr/bin/env genie

;; test stdout en stderr redirection:
;; * script writes to dynamic vars *out* and *err*
;; * nrepl takes care of the bindings, so it will 'catch' these writes.
;; * nrepl will then pass the contents to the client in the fields :out and :err
;; * the (babashka/tcl) client will then read these fields and write the
;;   contents on its dynamic vars *out* and *err* (or directly to stdout/stderr
;;   in Tcl)
;; * Babashka takes care that *out* and *err* are bound to the
;; * stdout/err of the client process.

(ns test-stdout-stderr
  (:require [ndevreeze.cmdline :as cl]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]))

(def cli-options
[["-c" "--config CONFIG" "Config file"]
 ["-h" "--help" "Show this help"]])

(defn standard-error
"Print something to stdout and stderr"
[opt ctx arguments]
(println "Next line to stderr (this line on stdout):")
(binding [*out* *err*]
  (println "Hello, STDERR!"))
(println "Stdout again"))

(defn script [opt arguments ctx]
(standard-error opt ctx arguments))

;; expect context/ctx now as first parameter, a map.
(defn main [ctx args]
(cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test'
(defn -main
"Entry point from clj cmdline script.
   Need to call System/exit, hangs otherwise."
[& args]
(cl/check-and-exec "" cli-options script args {:cwd "."})
(System/exit 0))
