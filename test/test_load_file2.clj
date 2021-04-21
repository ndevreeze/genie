#! /usr/bin/env genie.clj

(ns test-load-file2
  "require genied.client namespace, use load-relative-file afterwards,
   and then require the namespace of the loaded lib."
  (:require
   [ndevreeze.cmdline :as cl]
   [genied.client :as client]))

(client/load-relative-file "test_load_file_lib.clj")

(require '[test-load-file-lib :as lib])

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn script
  "Default script"
  [opt _arguments ctx]
  (println "ctx: " ctx)
  (println "test-load-file2, using (client/load-relative-file)")
  (lib/data-csv opt ctx))

(defn main
  "Main for use with genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

(defn -main
  "Entry point from clj cmdline script.
  Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
