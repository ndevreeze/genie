#! /usr/bin/env bb

;; for using raynes.fs (clj-commons/fs):
;; export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

(ns genie
  (:require [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            ;; [java-time :as time]
            ;; [ndevreeze.logger :as log] ;; does not work: Message:  Could not resolve symbol: clojure.lang.LockingTransaction/isRunning
            ))

(def cli-options
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-m" "--main MAIN" "Main function to call, namespaced. Default empty: get from script ns-decl"]
   ["-l" "--logdir LOGDIR" "Directory for client log. Leave empty for no logging"]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]
   [nil "--noload" "Do not load libraries and scripts, assume this has been done before"]
   [nil "--nocheckserver" "Do not perform server checks when an error occurs"]
   [nil "--nosetloader" "Do not set dynamic classloader before loading libraries and script"]
   [nil "--nomain" "Do not call main function after loading"]
   [nil "--nonormalize" "Do not normalize parameters to script (e.g. relative paths)"]])

(def ^:dynamic *verbose*
  "Dynamic var, set to true when -verbose cmdline option given.
   Used by function `debug` below"
  false)

(def ^:dynamic *logfile*
  "Dynamic var, set to a logfile name when logs should be saved in a file.
   Used by function `log` below"
  nil)

;; Using ndevreeze/logger and also java-time in Babashka gives some errors. So use this poor man's version for now.
(def log-time-pattern (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSSZ"))

(defn current-timestamp
  "Return current timestamp in a format suitable for a filename.
   In current timezone"
  []
  (.format (java.time.ZonedDateTime/now) log-time-pattern))

(defn log
  "Log to the dynamically set file.
   If dynamic var *logfile* is set, append to this file.
   Also add timestamp."
  [level msg]
  (let [msg2 (format "[%s] [%-5s] %s\n" (current-timestamp) level (str/join " " msg))]
    (binding [*out* *err*]
      (print msg2)
      (flush))
    (when *logfile*
      (spit *logfile* msg2  :append true))))

;; some poor man's logging for now
(defn warn
  "Log warning, always"
  [& msg]
  (log "WARN" msg))

(defn info
  "Log always"
  [& msg]
  (log "INFO" msg))

(defn debug
  "Log if -verbose is given"
  [& msg]
  (when *verbose*
    (log "DEBUG" msg)))

(defn log-stderr
  "Redirect script *err* output to both *err* and log-file"
  [msg]
  (binding [*out* *err*]
    (print msg)
    (flush))
  (when *logfile*
    (spit *logfile* msg :append true)))

(defn current-timestamp-file
  "Return current timestamp in a format suitable for a filename.
   In current timezone"
  []
  (let [now (java.time.ZonedDateTime/now)
        pattern (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss")]
    (.format now pattern)))

(defn log-file
  "Determine log-file based on --logdir option.
   Leave empty for no log file"
  [opt]
  (when-let [logdir (:logdir opt)]
    (fs/file logdir (format "genie-%s.log" (current-timestamp-file)))))

(defn bencode-value
  [val]
  (try (String. val)
       (catch Exception e
         (str "seq: " (str/join ", " (map #(String. %) val))))))

(defn println-result
  [result]
  (info "result: "result)
  (info "status:")
  (let [status (get result "status")]
    (doseq [status-item status]
      (info (String. status-item))))
  (doseq [key (keys result)]
    (info "have key in result:" key "=" (bencode-value (get result key)))))

(defn map-values
  "Map a function f over the values of a map m, and return a new map."
  [f m]
  (into {} (map (fn [[k v]] [k (f v)]) m)))

(defn readable-result
  "Assume result is a map, translate objects to readable strings"
  [result]
  (map-values bencode-value result))

(defn read-print-result
  "Read and print result channel until status is 'done'"
  [in]
  (let [result (b/read-bencode in)
        out-bytes (get result "out")
        err-bytes (get result "err")
        value (get result "value")
        ex (get result "ex")
        root-ex (get result "root-ex")
        status (get result "status")
        need-input (and status (= "need-input" (String. (first status))))
        done (and status (= "done" (String. (first status))))]
    (when true ;; need some verbose or debug argument here.
      (println-result result)
      (flush))
    (when out-bytes
      (print (String. out-bytes))
      (flush))
    (when err-bytes
      (log-stderr (String. err-bytes)))
    (when value
      (println "value: " (String. value)))
    (when ex
      (println "ex: " (String. ex)))
    (when root-ex
      (println "root-ex: " (String. root-ex)))
    (if (not (or done need-input))
      (recur in)
      {:done done :need-input need-input
       :result (readable-result result)})))

;; from https://book.babashka.org/#_interacting_with_an_nrepl_server
;; some tests with stdin
(defn nrepl-eval [host port expr]
  (let [s (java.net.Socket. host port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "clone"})
        clone-result (read-print-result in) ;; should save session-id for later cancellation.
        session-id (get (:result clone-result) "new-session")
        _ (info "Result of op=clone: " clone-result ", session-id=" session-id)
        _ (b/write-bencode out {"op" "eval" "session" session-id "code" expr})
        _ 0 #_(b/write-bencode out {"op" "stdin" "stdin" "Genie 1\nGenie 2\n"})
        _ (Thread/sleep 1000)
        _ 0 #_(b/write-bencode out {"op" "stdin" "stdin" "Genie 3\nGenie 4\n"})] ;; met deze doet 'ie niets, goede volgorde van op's nodig.
    (loop [it 0]
      (let [res (read-print-result in)]
        (info "res: " res ", iter=" it)
        (when (:need-input res)
          (info "Need more input!")
          (Thread/sleep 1000)
          (b/write-bencode out {"session" session-id "op" "stdin" "stdin" "Genie 3\nGenie 4\n"})
          (Thread/sleep 1000)
          _ (read-print-result in) ;; read ack
          (b/write-bencode out {"session" session-id "op" "stdin" "stdin" ""}) ; ook leeg string, dan einde?
          #_(b/write-bencode out {"op" "eval" "code" "(+ 3 4)"})
          (Thread/sleep 1000)
          _ (read-print-result in) ;; read ack
          (Thread/sleep 1000)
          (recur (inc it)))))
    (info "nrepl-eval loop has finished. Are we really done?")
    (when false
      (println "try read-print-result one more time:")
      (read-print-result in)
      (println "Really done now"))))

#_(defn nrepl-eval [host port expr]
    (let [s (java.net.Socket. host port)
          out (.getOutputStream s)
          in (java.io.PushbackInputStream. (.getInputStream s))
          _ (b/write-bencode out {"op" "clone"})
          _ (read-print-result in) ;; should save session-id for later cancellation.
          _ (b/write-bencode out {"op" "eval" "code" expr})]
      (read-print-result in)))

(defn create-context
  "Create script context, with current working directory (cwd)"
  [opt script]
  (let [script (fs/normalized script)
        cwd (fs/normalized ".")]
    {:cwd (str cwd)
     :client "babashka"
     :script (str script)
     :opt opt}))

(defn det-main-fn
  "Determine main function from the script.
   read ns-decl from the script-file, add '/main'
   use the last ns-decl in the script.
   If no ns-decl found, return `main` (root-ns)"
  [opt script]
  (or (:main opt)
      (with-open [rdr (clojure.java.io/reader script)]
        (if-let [namespaces
                 (seq (for [line (line-seq rdr)
                            :let [[_ ns] (re-find #"^\(ns ([^ \(\)]+)" line)]
                            :when (re-find #"^\(ns " line)]
                        ns))]
          (str (last namespaces) "/main")
          "main"))))

(defn exec-expression
  [ctx script main-fn script-params]
  (str "(genied.client/exec-script \"" script "\" '" main-fn " " ctx " [" script-params "])"))

;; TODO - prb want a config flag to not do this. It may be a bit too
;; magical in some circumstances.
(defn normalize-param
  "file normalise a parameter, so the server-process can find it, even though it has
   a different current-working-directory (cwd).
   - if a param starts with -, it's not a relative path.
   - if it starts with /, also not relative
   - if it start with ./, or is a single dot, then it is relative.
     Also useful when a path does start with a '-'
   - if it starts with a letter/digit/underscore, it could be relative. Check with file exists then."
  [param]
  (let [first-char (first param)]
    (cond (= \- first-char) param
          (= \/ first-char) param
          (= [\. \/] (take 2 param)) (fs/normalized param)
          (= "." param) (fs/normalized param)
          (fs/exists? param) (fs/normalized param)
          :else param)))

(defn normalize-params
  "Normalize cmdline parameters.
   Convert relative paths to absolute paths, so daemon can find the dirs/files.
   If --nonormalize given, this conversion is not done."
  [params nonormalize]
  (if nonormalize
    params
    (map normalize-param params)))

(defn quote-param
  "Quote a parameter with double quotes, for calling exec-script"
  [param]
  (str "\"" param "\""))

(defn quote-params
  [params]
  (str/join " " (map quote-param params)))

;; TODO - maybe start server when it's not started yet.
(defn exec-script
  "Execute given script with opt and script-params"
  [{:keys [port verbose nonormalize] :as opt} script script-params]
  (let [ctx (create-context opt script)
        script2 (fs/normalized script)
        main-fn (det-main-fn opt script2)
        script-params2 (-> script-params (normalize-params nonormalize) quote-params)
        expr (exec-expression ctx script2 main-fn script-params2)]
    (debug "nrepl-eval: " expr)
    (println "exec-script (client) to stdout before calling main")
    (binding [*out* *err*]
      (println "exec-script (client) to stderr before calling main"))
    (info "log with genie client logger before calling main")
    (nrepl-eval "localhost" port expr)
    (info "log with genie client logger after calling main")
    (println "exec-script (client) to stdout after calling main")
    (binding [*out* *err*]
      (println "exec-script (client) to stderr after calling main"))))

(defn print-help
  "Print help when --help given, or errors, or no script"
  [{:keys [summary options arguments errors] :as opts}]
  (println "genie.clj - babashka script to run scripts in Genie daemon")
  (println summary)
  (println)
  (println "Current options:" options)
  (println "Current arguments:" arguments)
  (when (empty? arguments)
    (println "  Need a script to execute"))
  (when errors
    (println "Errors:" errors)))

(defn main
  "Main function"
  []
  (let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)
        opt (:options opts)
        args (:arguments opts)]
    (binding [*verbose* (-> opts :options :verbose)
              *logfile* (log-file (:options opts))]
      ;;      (println "logfile: " (str *logfile*))
      #_(debug "Sleep 3 seconds at the start...")
      #_(Thread/sleep 3000)
      (debug "*command-line-args* = " *command-line-args*)
      (debug "opts = " opts)
      (debug "opt=" opt ", args=" args)
      (if (or (:help opt) (:errors opt) (empty? args))
        (print-help opts)
        (try
          (exec-script opt (first args) (rest args))
          (catch Exception e
            (warn "caught exception: " e)))))))

(main)
