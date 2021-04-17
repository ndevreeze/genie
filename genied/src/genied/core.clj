(ns genied.core
  "Genied core with main function and do-script"
  (:gen-class)
  (:require [genied.classloader :as loader]
            [genied.client :as client]
            [genied.diagnostics :as diag]
            [genied.state :as state]
            [ndevreeze.logger :as log]
            [ndevreeze.cmdline :as cl]
            [nrepl.server :as nrepl]
            [me.raynes.fs :as fs]))

(def cli-options
  "Command line options"
  [["-c" "--config CONFIG" "Config file"
    :default (or (when-let [dir (System/getenv "GENIE_CONFIG_DIR")]
                   (fs/file (fs/expand-home dir) "genie.edn"))
                 "~/.config/genie/genie.edn")]
   ["-h" "--help" "Show this help"]
   ["-p" "--port PORT" "TCP port to serve on"
    :default 7888 :parse-fn #(Integer/parseInt %)]
   ["-v" "--verbose" "Diagnostics wrt classloaders; log-level DEBUG"]])

#_(def cli-options
    "Command line options"
    [["-c" "--config CONFIG" "Config file"
      :default (or (when-let [dir (System/getenv "GENIE_CONFIG_DIR")]
                     (fs/file (fs/normalized (fs/expand-home dir)) "genie.edn"))
                   "~/.config/genie/genie.edn")]
     ["-h" "--help" "Show this help"]
     ["-p" "--port PORT" "TCP port to serve on"
      :default 7888 :parse-fn #(Integer/parseInt %)]
     ["-v" "--verbose" "Diagnostics wrt classloaders; log-level DEBUG"]])

#_(def cli-options
    "Command line options"
    [["-c" "--config CONFIG" "Config file"
      :default "~/.config/genie/genie.edn"]
     ["-h" "--help" "Show this help"]
     ["-p" "--port PORT" "TCP port to serve on"
      :default 7888 :parse-fn #(Integer/parseInt %)]
     ["-v" "--verbose" "Diagnostics wrt classloaders; log-level DEBUG"]])

#_(defn config-dir
    "Determine location of config directory
   By checking in this order:
   - config in cmdline options
   - GENIE_CONFIG_DIR
   - ~/bin"
    [opt]
    (fs/expand-home
     (or (:config opt)
         (System/getenv "GENIE_CONFIG_DIR")
         "~/.config/genie")))

(defn do-script
  "Main user defined function for genied"
  [{:keys [port config verbose] :as opt} arguments ctx]
  (when (:verbose opt)
    (alter-var-root #'diag/*verbose* (constantly true)))
  (log/init {:location :home :name "genied"
             :level (if verbose :debug :info)})
  (log/debug "genied started")
  (log/info "Using config: " config)
  (log/debug "Opt given: " opt)
  (log/debug "Rest arguments given: " arguments)
  (log/debug "Context (ctx): " ctx)

  (diag/print-diagnostic-info "start of do-script (genied)")
  (loader/init-dynamic-classloader!)

  (log/debug "init dynamic classloader done: " (state/get-classloader))
  (diag/print-diagnostic-info "after init-dynamic-classloader!")

  (loader/mark-project-libraries)
  (loader/load-startup-libraries opt)

  (state/set-out-streams! *out* *err*)

  (log/debug "Starting daemon on port " port)
  (state/set-daemon! (nrepl/start-server :port port))
  (log/info "nrepl daemon started on port: " port)

  (diag/print-diagnostic-info "after start-server")

  (client/init) ;; dummy for now, this also makes sure the namespace is loaded.
  (diag/print-diagnostic-info "end of do-script (genied)"))

(defn -main
  "Main function, starting point"
  [& args]
  (cl/check-and-exec "Description of genied"
                     cli-options do-script args))

(comment
  (-main "-p" "7889")

  )
