#! /usr/bin/env genie.clj

(ns {{namespace}}
  (:require 
   [ndevreeze.logger :as log]
   [ndevreeze.cmdline :as cl]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"
    :default "FILL IN"]
   ["-v" "--verbose" "Enable verbose/debug logging"]
   ["-h" "--help" "Show this help"]])

(defn script [opt arguments ctx]
  (log/init {:location :home :name "{{namespace}}" :cwd (:cwd ctx)
             :level (if (:verbose opt) :debug :info)})
  (log/info (format "script: %s" (-> ctx :script)))
  ;; (log/debug "script: {{script}}")
  (log/debug (format "script: %s" (:script ctx)))
  (log/debug opt ctx)
  (log/debug "arguments: " arguments)
  (log/debug "ctx: " ctx)

  (try
    ;; add expressions here.
    (catch Exception e
      (log/error "Caught error:" e))
    (finally
      ;; not sure if finally is needed here.
      ))

  (log/close))

(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; call with: 'clj -m {{namespace}}'
(defn -main
  "Entry point from clj cmdline script. Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
