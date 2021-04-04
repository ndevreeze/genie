;; client/script facing functions

(ns genied.client
  (:gen-class)
  (:require [genied.classloader :as loader]
            [genied.diagnostics :as diag]
            [genied.state :as state]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]
            [clojure.string :as str]))

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
   Use ctx to also log script-name on server-side"
  [ctx label]
  (diag/print-diagnostic-info (str (-> ctx :script (or "") fs/base-name) "/" label)))

(defn load-library
  "Wrapper around classloader-ns version"
  [lib version]
  (loader/load-library lib version))

;; TODO - Use cmdline options (both server and client) to determine if
;; classloader and load-file need to be done. And also exec main,
;; maybe want to do pre-loading.

(def ^:dynamic *script-dir*
  "Dynamic var, set when loading a script-file.
   Used by load-relative-file below"
  nil)

(defn println-server-out
  "Print a line to the server *out*"
  [msg]
  (binding [*out* (:out (state/get-out-streams))]
    (println msg)))

(defn println-server-err
  "Print a line to the server *err*"
  [msg]
  (binding [*out* (:err (state/get-out-streams))]
    (println msg)))

;; TODO - maybe in a separate logger namespace, specifically for server logging.
(defn log-server-debug
  "Log a message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :debug forms))

(defn log-server-info
  "Log a message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :info forms))

(defn log-server-warn
  "Log a message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :warn forms))

;; standard definition of main-function:
;; (defn main [ctx & args]
;;   (cl/check-and-exec "" cli-options script args ctx))
(defn exec-script
  "Wrapper around load-script-libraries, load-file, and call-main."
  [script main-fn {:keys [cwd script opt] :as ctx} script-params]
  (try
    (log-server-debug "exec-script - start")
    (log-server-debug "script=" script ", main-fn=" main-fn ", ctx=" ctx
                      ", script-params=" script-params)
    (print-diagnostic-info {} "start client")
    (when-not (:nosetloader opt)
      (set-dynamic-classloader!)
      (print-diagnostic-info {} "after set-dyn3!"))
    (when-not (:noload opt)
      (loader/load-script-libraries ctx script)
      (print-diagnostic-info {} "after loading client libraries")
      (binding [*script-dir* (fs/parent script)]
        (load-file script)))
    (log-server-debug "load-file done: " script)
    ;; main-fn is a symbol given by client. After load-file, eval
    ;; should work.
    (when-not (:nomain opt)
      ((eval main-fn) ctx script-params))
    (catch Exception e
      (log-server-warn "Exception during script exec: " e))
    (finally
      (log-server-debug "exec main-fn done: " main-fn))))

(defn load-relative-file
  "Load a file relative to the currently loading script"
  [path]
  (load-file (str (fs/file *script-dir* path))))
