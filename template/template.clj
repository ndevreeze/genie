#! /usr/bin/env genie

(ns {{namespace}}
  (:require 
   [clojure.data.csv :as csv]
   [clojure.edn :as edn]
   [clojure.java.io :as io]            
   [clojure.java.jdbc :as jdbc]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [java-time :as time]
   [me.raynes.fs :as fs]
   [nrepl.server :as nrepl]
   [ontodev.excel :as xls]

   [ndevreeze.flexdb :as db]
   [ndevreeze.logger :as log]
   [ndevreeze.cmdline :as cl]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"
    :default "FILL IN"]
   ["-h" "--help" "Show this help"]])

(defn script [opt arguments ctx]
  (log/init {:location :cwd :name "{{namespace}}" :cwd (:cwd ctx)})
  (println "script: {{script}}")
  (println opt ctx)
  (println "arguments: " arguments)
  (println "ctx: " ctx))

;; expect context/ctx now as first parameter, a map.
;; 2021-03-13: now without ampersand before args:
(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; call with: 'clj -m {{namespace}}'
;; call with: 'clj -M {{script}}' does not seem to work.
;; TODO - maybe need solution if script is called from another directory, in which case deps.edn cannot be found, which is used for bootstrapping.
;;        or do use the -M option for giving the script path?
(defn -main
  "Entry point from clj cmdline script. Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
