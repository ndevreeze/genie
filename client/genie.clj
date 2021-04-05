#! /usr/bin/env bb

;; for using raynes.fs (clj-commons/fs):
;; export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

(ns genie
  (:require [babashka.process :refer [process]]
            [babashka.wait :as wait]
            [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]))

(def cli-options
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-m" "--main MAIN" "Main function to call, namespaced. Default empty: get from script ns-decl"]
   ["-l" "--logdir LOGDIR" "Directory for client log. Leave empty for no logging"]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]
   [nil "--max-lines MAX-LINES" "Maximum number of lines to read from stdin and pass to daemon in one message"
    :default 1024
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 %) "Must be a number greater than 0"]]
   [nil "--noload" "Do not load libraries and scripts, assume this has been done before"]
   [nil "--nocheckdaemon" "Do not perform daemon checks when an error occurs"]
   [nil "--nosetloader" "Do not set dynamic classloader before loading libraries and script"]
   [nil "--nomain" "Do not call main function after loading"]
   [nil "--nonormalize" "Do not normalize parameters to script (e.g. relative paths)"]
   ;; and some admin commands.
   [nil "--list-sessions" "List currently open/running sessions/scripts"]
   [nil "--kill-sessions SESSIONS" "csv list of (part of) sessions/scripts to kill, or 'all'"]
   [nil "--start-daemon" "Start daemon running on port"]
   [nil "--stop-daemon" "Stop daemon running on port"]
   [nil "--restart-daemon" "Restart daemon running on port"]])

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
  (let [msg (format "[%s] [%-5s] %s\n" (current-timestamp) level (str/join " " msg))]
    (binding [*out* *err*]
      (print msg)
      (flush))
    (when *logfile*
      (spit *logfile* msg  :append true))))

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

;; some functions to determine locations of java, genied.jar and config.
(defn java-binary
  "Determine location of java binary.
   By checking in this order:
   - GENIE_JAVA_CMD
   - JAVA_CMD
   - JAVA_HOME
   - java"
  []
  (or (System/getenv "GENIE_JAVA_CMD")
      (System/getenv "JAVA_CMD")
      (when-let [java-home (System/getenv "JAVA_HOME")]
        (str (fs/file java-home "bin" "java")))
      "java"))

(defn first-file
  "Find first file according to glob-specs in dir.
   Return nil if none found, a string otherwise. Search in dir (no
  sub-dirs). Check glob-specs seq in order"
  [dir glob-specs]
  (when (seq glob-specs)
    (if-let [files (fs/glob (fs/file dir) (first glob-specs))]
      (str (first files))
      (recur dir (rest glob-specs)))))

(defn first-dir
  "Find first dir in dirs seq that exists and return it.
   Return nil if none found."
  [dirs]
  (when-let [dir (first dirs)]
    (let [dir (fs/expand-home dir)]
      (if (fs/exists? dir)
        (str dir)
        (recur (rest dirs))))))

(defn genie-home
  "Determine location of genie home
   By checking in this order:
   - GENIE_HOME
   - /opt/genie
   - /usr/local/lib/genie
   - ~/tools/genie"
  []
  (or (System/getenv "GENIE_HOME")
      (first-dir ["/opt/genie" "/usr/local/lib/genie" "~/tools/genie"])))

(defn genied-jar
  "Determine location of genied jar file
   By checking in this order:
   - GENIE_JAR (explicit)
   - GENIE_HOME (genied.jar or any jar with 'genied' in its name in this dir)
   - /opt/genie (same as GENIE_HOME)
   - /usr/local/lib/genie (same as GENIE_HOME)"
  []
  (or (System/getenv "GENIE_JAR")
      (first-file (genie-home) ["genied.jar" "genied*.jar"])))

;; 2021-04-02: copied from babashka: test/babashka/impl/nrepl_server_test.clj
(defn bytes->str [x]
  (if (bytes? x) (String. (bytes x))
      (str x)))

;; 2021-04-02: copied from babashka: test/babashka/impl/nrepl_server_test.clj
(defn read-msg [msg]
  (let [res (zipmap (map keyword (keys msg))
                    (map #(if (bytes? %)
                            (String. (bytes %))
                            %)
                         (vals msg)))
        res (if-let [status (:status res)]
              (assoc res :status (mapv bytes->str status))
              res)
        res (if-let [status (:sessions res)]
              (assoc res :sessions (mapv bytes->str status))
              res)]
    res))

(defn msg-id
  "Create a UUID for adding to nRepl message"
  []
  (str (java.util.UUID/randomUUID)))

(defn println-result
  "For verbose/debug printing of nrepl messages"
  [result]
  (debug "result: " result)
  (debug "status:")
  (let [status (get result "status")]
    (doseq [status-item status]
      (debug status-item)))
  (doseq [key (keys result)]
    (debug "have key in result:" key "=" (get result key))))


;; TODO - merge with read-print-result.  but take care of recur in
;; combination with both output and input. If this one recurs without
;; printing output, we lose some output.
(defn read-result
  "Read result channel until status is 'done'.
   Return result map with keys :done, :need-input and keys of nrepl-result"
  [in]
  (let [{:keys [out err value ex root-ex status] :as result} (-> (b/read-bencode in) read-msg)
        need-input (some #{"need-input"} status)
        done (some #{"done"} status)]
    (when *verbose*
      (println-result result)
      (flush))
    (if (not (or done need-input))
      (recur in)
      (merge {:done done :need-input need-input} result))))

(defn read-print-result
  "Read and print result channel until status is 'done'.
   Return result map with keys :done, :need-input and keys of nrepl-result"
  [in]
  (loop [old-value nil]
    (let [{:keys [out err value ex root-ex status] :as result}
          (-> (b/read-bencode in) read-msg)
          need-input (some #{"need-input"} status)
          done (some #{"done"} status)]
      (when *verbose*
        (println-result result)
        (flush))
      (when out
        (print out)
        (flush))
      (when err
        (log-stderr err))
      (when value
        (if *verbose*
          (println "value: " value)))
      (when ex
        (println "ex: " ex))
      (when root-ex
        (println "root-ex: " root-ex))
      (if (not (or done need-input))
        (recur (or old-value value))
        (merge {:done done :need-input need-input :value old-value} result)))))

(defn read-lines
  "Read at least one line from reader, possibly more.
   genied 'need-input', so we need at least one line (or char).
   Use .ready to see if we can read more. This should be faster.
   Each line returned ends with a new-line.
   Return one single string, possibly containing multiple lines.
   If input is exhausted, return nil"
  [{:keys [max-lines] :as opt} rdr]
  (loop [lines []
         line (.readLine rdr)
         read 1]
    (if line
      (if (and (.ready rdr) (< read max-lines))
        (recur (conj lines (str line "\n"))
               (.readLine rdr)
               (inc read))
        (apply str (conj lines (str line "\n"))))
      (if (empty? lines)
        nil
        (apply str lines)))))

(defn connect-nrepl
  "Connect to an nRepl server and open in and out TCP streams"
  [{:keys [port verbose] :as opt}]
  (let [s (java.net.Socket. "localhost" port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))]
    {:socket s
     :out out
     :in in}))

(def session-atom (atom nil))

(defn exec-expression
  [ctx script main-fn script-params]
  (str "(genied.client/exec-script \"" script "\" '" main-fn " " ctx " [" script-params "])"))

(defn nrepl-eval [opt host port {:keys [eval-id] :as ctx} script main-fn script-params]
  (let [{:keys [socket out in]} (connect-nrepl opt)
        _ (b/write-bencode out {"op" "clone" "id" (msg-id)})
        session (-> (read-result in) :new-session)
        ctx (assoc ctx :session session)
        expr (exec-expression ctx script main-fn script-params)]
    (try
      (debug "nrepl-eval: " expr)
      (reset! session-atom {:session session :eval-id eval-id :out out :in in})
      (b/write-bencode out {"op" "eval" "session" session "id" eval-id "code" expr})
      (loop [it 0]
        (let [res (read-print-result in)]
          (debug "res: " res ", iter=" it)
          (when (:need-input res)
            (debug "Need more input!")
            (let [lines (read-lines opt *in*)]
              (b/write-bencode out {"session" session "id" (msg-id) "op" "stdin" "stdin" lines})
              (read-result in) ;; read ack
              (recur (inc it))))))
      (catch Exception e
        (warn "Caught exception: " e))
      (finally
        ;; this close-op should always be executed, even when an uncaught exception occurs.
        (b/write-bencode out {"op" "close" "session" session "id" (msg-id)})
        (read-result in)
        (reset! session-atom nil)
        (debug "Wrote op=close")))))

(defn create-context
  "Create script context, with current working directory (cwd)"
  [opt script]
  (let [script (fs/normalized script)
        cwd (fs/normalized ".")]
    {:cwd (str cwd)
     :client "babashka"
     :script (str script)
     :opt opt
     :eval-id (msg-id)}))

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

(defn normalize-param
  "file normalise a parameter, so the daemon-process can find it, even though it has
   a different current-working-directory (cwd).
   - if a param starts with -, it's not a relative path.
   - if it starts with /, also not relative
   - if it start with ./, or is a single dot, then it is relative.
     Also useful when a path does start with a '-'
   - if it starts with a letter/digit/underscore, it could be relative. Check with file exists then.
   If --nonormalize given, this conversion is not done."
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

;; TODO - maybe start daemon when it's not started yet.
;; (wait/wait-for-port "localhost" 8080 {:timeout 1000 :pause 1000}) could be helpful here.
(defn exec-script
  "Execute given script with opt and script-params"
  [{:keys [port verbose nonormalize] :as opt} script script-params]
  (let [ctx (create-context opt script)
        script (fs/normalized script)
        main-fn (det-main-fn opt script)
        script-params (-> script-params (normalize-params nonormalize) quote-params)]
    (nrepl-eval opt "localhost" port ctx script main-fn script-params)))

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

(defn do-admin-command
  "Perform an admin command on daemon"
  [{:keys [socket out in] :as admin-session} command & [{:keys [no-read] :as opt}]]
  (try
    (b/write-bencode out command)
    (if no-read
      (debug "Not reading response")
      (let [res (read-print-result in)]
        (debug "Status: " (str/join ", " (:status res)))
        res))
    (catch Exception e
      (warn "Caught exception: " e))))

(defn kill-script
  "Kill the running script when a shutdown signal is received.
   Writing op=interrupt and then op=close seems to work, and the
  script stops. Reading the response however does not (for both ops),
  causes java.lang.ArithmeticException: integer overflow in
  read-bencode. So do not read result for now"
  []
  (debug "Running session: " @session-atom)
  (if-let [{:keys [session eval-id in out] :as admin-session} @session-atom]
    (do
      (warn "Shutdown hook triggered, stopping script")
      (do-admin-command admin-session {"op" "interrupt" "session" session "interrupt-id" eval-id} {:no-read true})
      (debug "Wrote op=interrupt")
      (do-admin-command admin-session {"op" "close" "session" session "id" (msg-id)} {:no-read true})
      (debug "wrote op=close"))
    (debug "session already closed, do nothing")))

(defn admin-get-sessions
  "Get sessions given a current admin-session"
  [admin-session]
  (let [{:keys [sessions]}
        (do-admin-command admin-session {"op" "ls-sessions"})]
    sessions))

;; use daemon function, to also show other session info such as script, maybe also start-time.
(defn admin-list-sessions
  "List currently open/running sessions/scripts"
  [{:keys [port verbose] :as opt}]
  (let [admin-session (connect-nrepl opt)]
    (try
      (let [sessions (-> (do-admin-command admin-session {"op" "eval" "code" "(genied.client/list-sessions)"})
                         :value
                         edn/read-string
                         vals)]
        (println "Total #sessions:" (count sessions))
        (doseq [{:keys [session script]} sessions]
          (println (str "[" session "] " script))))
      (catch Exception e
        (warn "Caught exception: " e))
      (finally
        (debug "finally clause in admin-list-sessions")))))

(defn split-sessions
  "Split session list on a comma"
  [opt admin-session sessions]
  (if (= sessions "all")
    (admin-get-sessions admin-session)
    (str/split sessions #",")))

(defn find-session
  "Find session id(s) based on spec.
   Spec is either part of the UUID session id or part of the script
  being executed.
   Returns seq of session ids."
  [admin-session spec]
  (let [sessions (-> (do-admin-command admin-session
                                       {"op" "eval"
                                        "code" "(genied.client/list-sessions)"})
                     :value
                     edn/read-string
                     vals)
        re-pat (re-pattern spec)]
    (concat (filter #(re-find re-pat (:session %)) sessions)
            (filter #(re-find re-pat (:script %)) sessions))))

(defn admin-kill-sessions
  "Kill the sessions with the given (part of) ids or (part of) script names.
   Or 'all' to kill all sessions."
  [opt sessions]
  (println "Kill sessions:" sessions)
  (let [admin-session (connect-nrepl opt)
        session-specs (split-sessions opt admin-session sessions)
        sessions (mapcat #(find-session admin-session %) session-specs)]
    (doseq [{:keys [session script eval-id]} sessions]
      (println (str "Closing session: [" session "] " script))
      ;; giving both interrupt and close results in error messages and
      ;; the genie.clj client process hanging, as no :done message is
      ;; received. Only interrupt does not work, but only close
      ;; does. So do this for now.
      #_(do-admin-command admin-session {"op" "interrupt" "session" session "interrupt-id" eval-id})
      (do-admin-command admin-session {"op" "close" "session" session}))))

(defn admin-stop-daemon!
  "Stop the daemon running on given port"
  [opt]
  (let [admin-session (connect-nrepl opt)]
    (do-admin-command admin-session {"op" "eval" "code" "(genied.client/stop-daemon!)"} {:no-read true}))
  (println "Stopped daemon"))

(defn admin-start-daemon!
  "Start the daemon process on given port.
   Use environment vars and check directories for locations of java
  binary and genied.jar"
  [opt]
  (println "Starting daemon on port:" (:port opt))
  (let [pr (process [(java-binary) '-jar (genied-jar) '-p (:port opt)])
        _ (println "Process started, waiting (max 60 seconds) until port is available")
        _ (println "cmd =" (str/join " " (:cmd pr)))
        res (wait/wait-for-port "localhost" (:port opt) {:timeout 60000 :pause 1000})]
    (if res
      (println "Ok, started in" (:took res) "msec")
      (do
        (println "Failed to start server, res=nil, process =" pr)
        ;; reading (deref pr) blocks.
        #_(println ", @pr=" @pr)))))

(defn admin-command!
  "Perform an admin command instead of running a script"
  [{:keys [list-sessions kill-sessions start-daemon stop-daemon restart-daemon] :as opt} args]
  (cond list-sessions
        (admin-list-sessions opt)
        kill-sessions
        (admin-kill-sessions opt kill-sessions)
        stop-daemon
        (admin-stop-daemon! opt)
        start-daemon
        (admin-start-daemon! opt)
        :else
        (warn "Unknown admin command: " opt)))

(defn admin-command?
  "Return true if an admin command is given as a cmdline option"
  [{:keys [list-sessions kill-sessions start-daemon stop-daemon restart-daemon] :as opt}]
  (or list-sessions kill-sessions start-daemon stop-daemon restart-daemon))

(defn main
  "Main function"
  []
  (let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)
        opt (:options opts)
        args (:arguments opts)]
    (binding [*verbose* (-> opts :options :verbose)
              *logfile* (log-file (:options opts))]
      (debug "*command-line-args* = " *command-line-args*)
      (debug "opts = " opts)
      (debug "opt=" opt ", args=" args)
      (cond (admin-command? opt)
            (admin-command! opt args)
            (or (:help opt) (:errors opt) (empty? args))
            (print-help opts)
            :else
            (try
              (-> (Runtime/getRuntime) (.addShutdownHook (Thread. kill-script)))
              (exec-script opt (first args) (rest args))
              (catch Exception e
                (warn "caught exception: " e))))
      ;; do not print/return the result of the last expression:
      nil)))

(main)
