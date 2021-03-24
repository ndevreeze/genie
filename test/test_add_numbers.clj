#! /usr/bin/env genie

;; test adding numbers, including parsing from cmdline.

(ns test-add-numbers
  (:require 
   [ndevreeze.cmdline :as cl]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn divide-by-0
  [opt ctx]
  (println "5 / 0 = " (/ 5 0)))

(defn add-numbers
  "Add given numbers, which are given as strings"
  [arguments]
  (apply + (map #(Integer/parseInt %) arguments)))

(defn script [opt arguments ctx]
;;  (println "ctx: " ctx)
  (println "The sum of" arguments "is" (add-numbers arguments)))

(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test-divide-by-0

;; TODO - maybe need solution if script is called from another
;;        directory, in which case deps.edn cannot be found, which is
;;        used for bootstrapping.  or do use the -M option for giving
;;        the script path?
(defn -main
  "Entry point from clj cmdline script. Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
