#! /usr/bin/env genie.clj

;; test is command line options of script can conflict with cmdline
;; options of the (babashka) client.  test with -p and -v

;; e.g. call with:
;; ../client/genie.clj -p 7889 ../test/test_params.clj -v -p 1234 abc

;; results in:
;; Given cmdline options:  {:verbose true, :port 1234}
;; Given cmdline arguments:  [abc]

(ns test-params
  "test is command line options of script can conflict with cmdline
  options of the (babashka) client.  test with -p and -v"
  (:require
   [ndevreeze.cmdline :as cl]))

(def cli-options
  "Cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-p" "--port PORT" "Port to use in script"]
   ["-v" "--verbose" "Verbose logging in script"]
   ["-h" "--help" "Show this help"]])

(defn script
  "Main script called from `main` and `-main`"
  [opt arguments _ctx]
  ;;  (println "ctx: " ctx)
  (println "Given cmdline options: " opt)
  (println "Given cmdline arguments: " arguments))

(defn main
  "Main from genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-divide-by-0

(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
