#! /usr/bin/env genie

;; require genied.client namespace, use load-relative-file afterwards,
;; and then require the namespace of the loaded lib.

(ns test-load-file2
  (:require 
   [ndevreeze.cmdline :as cl]
   [genied.client :as client]
   [clojure.java.classpath :as cp]))

(client/load-relative-file "test_load_file_lib.clj")

(require '[test-load-file-lib :as lib])

(def cli-options
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn script [opt arguments ctx]
  (println "ctx: " ctx)
  (println "test-load-file2, using (client/load-relative-file")
  (lib/data-csv opt ctx)
  ;; classpath only contains Maven/.m2 jars.
  #_(println "classpath here: " (cp/classpath)))

(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-dyn-cl

(defn -main
  "Entry point from clj cmdline script. Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
