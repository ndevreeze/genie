(ns genied.core
  (:gen-class)
  (:require [genied.classloader :as loader]
            [genied.client :as client]
            [genied.diagnostics :as diag]
            [genied.sing-loader :as sing]
            [ndevreeze.logger :as log]
            [ndevreeze.cmdline :as cl]
            [nrepl.server :as nrepl]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"
    :default "~/.config/genie/genie.edn"]
   ["-h" "--help" "Show this help"]
   ["-p" "--port PORT" "TCP port to serve on"
    :default 7888 :parse-fn #(Integer/parseInt %)]
   ["-v" "--verbose" "Diagnostics wrt classloaders; log-level DEBUG"]])

(defn do-script
  "Main user defined function for genied"
  [opt arguments ctx]
  (when (:verbose opt)
    (alter-var-root #'diag/*verbose* (constantly true)))
  (log/init {:location :home :name "genied"
             :level (if (:verbose opt) :debug :info)})
  (log/debug "genied started")

  (log/debug "Opt given: " opt)
  (log/debug "Rest arguments given: " arguments)
  (log/debug "Context (ctx): " ctx)

  (diag/print-diagnostic-info "start of do-script (genied)")
  
  (loader/init-dynamic-classloader!)

  (log/debug "init dynamic classloader done: " (sing/get-classloader))
  (diag/print-diagnostic-info "after init-dynamic-classloader!")

  (loader/load-startup-libraries opt)
  
  (log/debug "Starting server on port " (:port opt))
  (defonce server (nrepl/start-server :port (:port opt)))
  (log/info "nrepl server started on port: " (:port opt))

  (diag/print-diagnostic-info "after start-server")

  (client/init) ;; dummy for now, this also makes sure the namespace is loaded.
  
  (diag/print-diagnostic-info "end of do-script (genied)"))

(defn -main [& args]
  (cl/check-and-exec "Description of genied"
                     cli-options do-script args))
