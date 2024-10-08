(ns genied.client
  "Client namespace - functions called by (babashka) clients"
  (:gen-class)
  (:require [genied.classloader :as loader]
            [genied.diagnostics :as diag]
            [genied.state :as state]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]
            [nrepl.server :as nrepl]))

(defn genied-version
  "Return version string"
  []
  "2024-08-17: version 4, cleaned up")

(defn init
  "Init client part, dummy for now"
  []
  (log/debug "Client/init"))

(defn get-classloader
  "Wrapper around classloader-ns version"
  []
  (state/get-classloader))

(defn set-dynamic-classloader!
  "Wrapper around classloader-ns version"
  []
  (loader/set-dynamic-classloader!))

;; [2021-03-06 20:13] should not need to call directly from client,
;; but want to do some tests.
(defn ensure-dynamic-classloader
  "Wrapper around classloader-ns version"
  []
  (loader/ensure-dynamic-classloader))

(defn print-diagnostic-info
  "Wrapper around diagnostics version.
   Use ctx to also log script-name on daemon-side"
  [ctx label]
  (diag/print-diagnostic-info
   (str (-> ctx :script (or "") fs/base-name) "/" label)))

(defn load-library
  "Wrapper around classloader-ns version"
  [lib version]
  (loader/load-library lib version))

(def ^:dynamic *script-dir*
  "Dynamic var, set when loading a script-file.
   Used by load-relative-file below"
  nil)

(defn println-daemon-out
  "Print a line to the daemon *out*"
  [msg]
  (binding [*out* (:out (state/get-out-streams))]
    (println msg)))

(defn println-daemon-err
  "Print a line to the daemon *err*"
  [msg]
  (binding [*out* (:err (state/get-out-streams))]
    (println msg)))

;; TODO - maybe in a separate logger namespace, specifically for daemon logging.
(defn log-daemon-debug
  "Log a message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :debug forms))

(defn log-daemon-info
  "Log a message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :info forms))

(defn log-daemon-warn
  "Log a message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :warn forms))

(def supported-protocol-versions
  "Seq of supported protocol versions"
  ["0.1.0"])

(defn supported-protocol-version?
  "Return true if protocol version is supported
   Not really used now, may be handy for future versions"
  [{:keys [protocol-version]}]
  (= protocol-version (first supported-protocol-versions)))

;; 2024-08-17: un-def this one for now. Maybe need again later. Or
;; clean up at some point.
#_(defn append-file-log
    "Dump text to file, without redirection of stderr etc.
   Add a newline at the end."
    [s]
    (spit "/tmp/genied-test.txt" (format "%s\n" s) :append true))

(defn exec-script
  "Wrapper around load-script-libraries, load-file, and call-main."
  [script main-fn {:keys [opt] :as ctx} script-params]
  (try
    (log-daemon-debug "exec-script - start")
    (log-daemon-debug "script=" script ", main-fn=" main-fn ", ctx=" ctx
                      ", script-params=" script-params)
    (state/add-session! ctx)
    (print-diagnostic-info {} "start client")
    (when-not (supported-protocol-version? ctx)
      (log-daemon-warn "Unsupported protocol version:" (:protocol-version ctx)
                       "for script:" script
                       ". Expected: " supported-protocol-versions)
      (binding [*out* *err*]
        (println "WARNING - Unsupported protocol version:"
                 (:protocol-version ctx)
                 ". Expected: " supported-protocol-versions)))
    (when-not (:nosetloader opt)
      (set-dynamic-classloader!)
      (print-diagnostic-info {} "after set-dyn3!"))
    (when-not (:noload opt)
      (let [deps-edn (loader/load-script-libraries ctx script)]
        (log-daemon-debug "deps-edn:" deps-edn))
      (print-diagnostic-info {} "after loading client libraries")
      (binding [*script-dir* (fs/parent script)]
        (load-file script)))
    (log-daemon-debug "load-file done: " script)
    ;; main-fn is a symbol given by client. After load-file, eval
    ;; should work.
    (when-not (:nomain opt)
      ((eval main-fn) ctx script-params))
    (catch Throwable e
      ;; 2024-08-10: also want stack-trace.
      ;; 2024-08-17: maybe get double stacktraces now. Maybe clean up later.
      (log-daemon-warn "Throwable during script exec: " e)
      (log-daemon-warn "Stack trace: " (with-out-str (clojure.stacktrace/print-stack-trace e)))
      ;; 2024-08-15: also just print error on stdout here, should be visible in the client then
      (println "Throwable during script exec: " e)
      (println (format "Stack trace: %s"
                       (with-out-str (clojure.stacktrace/print-stack-trace e))))
      ;; client needs to know too, should be with stack trace as well:
      ;; 2024-08-15: do not re-throw error for now, with possible premature exit on client-side.
      ;; 2024-08-17: do re-throw again.
      (throw e))
    (finally
      (log-daemon-debug "exec main-fn done: " main-fn)
      (state/remove-session! (:session ctx)))))

;; 2024-04-02: This one also called when running with clj/cider.
;; *script-dir* is nil in this case, maybe use this fact.
(defn load-relative-file
  "Load a file relative to the currently loading script"
  [path]
  (load-file (str (fs/file *script-dir* path))))

;; admin functions
(defn list-sessions
  "Show session info"
  []
  (log-daemon-debug "Called list-sessions")
  (state/get-sessions))

(defn stop-daemon!
  "Stop this daemon"
  []
  (log-daemon-info "Stopping daemon...")
  (nrepl/stop-server (state/get-daemon))
  (log-daemon-info "Stopped daemon")
  (System/exit 0))
