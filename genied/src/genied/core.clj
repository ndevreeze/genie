(ns genied.core
  "Genied core with main function and do-script"
  (:gen-class)
  (:require [genied.classloader :as loader]
            [genied.client :as client]
            [genied.diagnostics :as diag]
            [genied.state :as state]
            [me.raynes.fs :as fs]
            [ndevreeze.cmdline :as cl]
            [ndevreeze.logger :as log]
            [nrepl.server :as nrepl]))

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

(defn log-location
  "Determine log-file location from options and env"
  [_opt]
  (or (System/getenv "GENIE_LOG_DIR")
      :home))

(defn pre-init-daemon
  "Setup daemon.
   Part before starting the server and listen on TCP port"
  [{:keys [port config verbose] :as opt} arguments ctx]
  (log/init {:location (log-location opt) :name "genied"
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

  (state/set-out-streams! *out* *err*))

(defn post-init-daemon
  "Setup daemon
   Part after starting the server."
  [{:keys [port config verbose] :as opt} arguments ctx]
  (client/init) ;; dummy for now, this also makes sure the namespace is loaded.
  (diag/print-diagnostic-info "end of do-script (genied)"))

(defn do-script
  "Main user defined function for genied"
  [{:keys [port config verbose] :as opt} arguments ctx]
  (when (:verbose opt)
    (alter-var-root #'diag/*verbose* (constantly true)))
  (pre-init-daemon opt arguments ctx)

  (log/debug "Starting daemon on port " port)
  (state/set-daemon! (nrepl/start-server :port port))
  (log/info "nrepl daemon started on port:" port)
  (diag/print-diagnostic-info "after start-server")

  (post-init-daemon opt arguments ctx))

#_(defn do-script
    "Main user defined function for genied"
    [{:keys [port config verbose] :as opt} arguments ctx]
    (when (:verbose opt)
      (alter-var-root #'diag/*verbose* (constantly true)))
    (log/init {:location (log-location opt) :name "genied"
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
    (log/info "nrepl daemon started on port:" port)

    (diag/print-diagnostic-info "after start-server")

    (client/init) ;; dummy for now, this also makes sure the namespace is loaded.
    (diag/print-diagnostic-info "end of do-script (genied)"))

(defn -main
  "Main function, starting point"
  [& args]
  (cl/check-and-exec "Description of genied"
                     cli-options do-script args))

(comment
  ;; starting an nRepl server in Cider:
  (-main "-p" "7887")
  (-main "-v" "-p" "7887"))
