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
   ["-m" "--mark MARK_LIBRARIES" "Mark libraries from source (default), project_clj, none or external file"
    :default "source"]
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
  [{:keys [_port config _mark verbose] :as opt} arguments ctx]
  (log/init {:location (log-location opt) :name "genied"
             :level (if verbose :debug :info)})
  (log/debug "genied started")
  (log/info "Using config: " config)
  (log/debug "Opt given: " opt)
  ;; 2024-04-02: Also info for now, for mark parameter.
  (log/info "Opt given: " opt)

  (log/debug "Rest arguments given: " arguments)
  (log/debug "Context (ctx): " ctx)

  (diag/print-diagnostic-info "start of do-script (genied)")
  (loader/init-dynamic-classloader!)

  (log/debug "init dynamic classloader done: " (state/get-classloader))
  (diag/print-diagnostic-info "after init-dynamic-classloader!")

  (loader/mark-project-libraries opt)
  (loader/load-startup-libraries opt)

  (state/set-out-streams! *out* *err*))

(defn post-init-daemon
  "Setup daemon
   Part after starting the server."
  [{:keys [_port _config _verbose] :as _opt} _arguments _ctx]
  (client/init) ;; dummy for now, this also makes sure the namespace is loaded.
  (diag/print-diagnostic-info "end of do-script (genied)"))

;; see https://stackoverflow.com/questions/6546193/how-to-catch-an-exception-from-a-thread#6548203
;; and https://stackoverflow.com/questions/15855039/proxying-thread-uncaughtexceptionhandler-from-clojure#15855802
;; Thread.setDefaultUncaughtExceptionHandler(h)
(defn set-default-exception-handler
  "Set default exception handler for threads.
   Just printing/logging the exception.
   Wrt daemon process stopping (crashing?) sometimes in relation with raynes/conch,
   which starts Threads and uses Futures."
  [_opt]
  (log/info "Setting default exception handler for threads.")
  (let [h (reify Thread$UncaughtExceptionHandler
            (uncaughtException [this t e]
              (println t ": " e)
              (log/warn "Uncaught exception in thread:" t ":" e)))]
    (Thread/setDefaultUncaughtExceptionHandler h)))

(defn keep-alive-loop
  "Check (and make sure?) that main thread stays alive, even if nrepl or
  client sub-threads cause issues. E.g. using raynes/conch, that
  starts Threads and uses Futures.
  Sleep for 1 hour now each time - less logging, still useful"
  [_opt]
  (log/info "Starting keep-alive-loop")
  (while true
    (log/debug "In keep alive loop, sleeping 1 hour.")
    (Thread/sleep 3600000))
  (log/info "After keep-alive-loop (should not happen)"))

(defn do-script
  "Main user defined function for genied"
  [{:keys [port _config _verbose] :as opt} arguments ctx]
  (when (:verbose opt)
    (alter-var-root #'diag/*verbose* (constantly true)))

  (pre-init-daemon opt arguments ctx)

  (log/debug "Starting daemon on port " port)
  (state/set-daemon! (nrepl/start-server :port port))
  (log/info "nrepl daemon started on port:" port)
  (diag/print-diagnostic-info "after start-server")

  (set-default-exception-handler opt)

  (post-init-daemon opt arguments ctx)

  (keep-alive-loop opt))

(defn -main
  "Main function, starting point"
  [& args]
  (cl/check-and-exec "Description of genied"
                     cli-options do-script args))

(comment
  ;; starting an nRepl server in Cider:
  (-main "-p" "7887")
  (-main "-v" "-p" "7887"))
