#! /usr/bin/env bb

(ns genie
  "Genie client in Babashka"
  (:require [babashka.process :as p]
            [babashka.wait :as wait]
            [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.fs :as fs])
  (:import [java.io File]))

;; from raynes.fs, no home functions in babashka.fs
(let [homedir (io/file (System/getProperty "user.home"))
      usersdir (.getParent homedir)]
  (defn home
    "With no arguments, returns the current value of the `user.home` system
     property. If a `user` is passed, returns that user's home directory. It
     is naively assumed to be a directory with the same name as the `user`
     located relative to the parent of the current value of `user.home`."
    ([] homedir)
    ([user] (if (empty? user) homedir (io/file usersdir user)))))

;; from raynes.fs, no home functions in babashka.fs
(defn expand-home
  "If `path` begins with a tilde (`~`), expand the tilde to the value
  of the `user.home` system property. If the `path` begins with a
  tilde immediately followed by some characters, they are assumed to
  be a username. This is expanded to the path to that user's home
  directory. This is (naively) assumed to be a directory with the same
  name as the user relative to the parent of the current value of
  `user.home`. Return nil if path is nil"
  [path]
  (when path
    (let [path (str (fs/normalize path))]
      (if (.startsWith path "~")
        (let [sep (.indexOf path File/separator)]
          (if (neg? sep)
            (home (subs path 1))
            (io/file (home (subs path 1 sep)) (subs path (inc sep)))))
        (io/file path)))))

(defn normalized
  "From Raynes/fs, combination of absolutize and normalize.
   Also replace backslashes by forward slashes, wrt calling nRepl"
  [path]
  (str/replace (fs/normalize (fs/absolutize path))
               #"\\" "/"))

(defn os-name
  "Return name of operating system
   using (System/getProperty \"os.name\")"
  []
  (System/getProperty "os.name"))

(defn windows?
  "Return true iff os-name has Windows"
  []
  (boolean (re-find #"^Windows" (os-name))))

(def cli-options
  "Genie client command line options"
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-m" "--main MAIN" "main ns/fn to call. Empty: get from script ns-decl"]
   ["-l" "--logdir LOGDIR" "Directory for client log. Empty: no logging"]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help" "Show help"]
   [nil "--max-lines MAX-LINES" "Max #lines to read/pass in one message"
    :default 1024
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a number greater than 0"]]
   [nil "--noload" "Do not load libraries and scripts"]
   [nil "--nocheckdaemon" "Do not perform daemon checks on errors"]
   [nil "--nosetloader" "Do not set dynamic classloader"]
   [nil "--nomain" "Do not call main function after loading"]
   [nil "--nonormalize" "Do not normalize parameters to script (rel. paths)"]
   ;; and some admin commands.
   [nil "--list-sessions" "List currently open/running sessions/scripts"]
   [nil "--kill-sessions SESSIONS" "csv list of (part of) sessions, or 'all'"]
   [nil "--start-daemon" "Start daemon running on port"]
   [nil "--stop-daemon" "Stop daemon running on port"]
   [nil "--restart-daemon" "Restart daemon running on port"]
   [nil "--max-wait-daemon MAX_WAIT_SEC" "Max seconds to wait for daemon to start"
    :default 60
    :parse-fn #(Integer/parseInt %)
    :validate [pos? "Must be a number greater than 0"]]])

(def ^:dynamic *verbose*
  "Dynamic var, set to true when -verbose cmdline option given.
   Used by function `debug` below"
  false)

(def ^:dynamic *logfile*
  "Dynamic var, set to a logfile name when logs should be saved in a file.
   Used by function `log` below"
  nil)

(def genie-src-root
  "var, used by source-jar function.
   need *file* value during load-time. During call-time (of
  source-jar), it has been changed to install.clj."
  (fs/normalize (fs/file *file* "../..")))

(def log-time-pattern
  "log timestamp format.
  Using ndevreeze/logger and also java-time in Babashka gives some
  errors. So use this poor man's version for now."
  (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSSZ"))

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
  (let [msg (format "[%s] [%-5s] %s\n" (current-timestamp) level
                    (str/join " " msg))]
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
        pattern (java.time.format.DateTimeFormatter/ofPattern
                 "yyyy-MM-dd'T'HH-mm-ss")]
    (.format now pattern)))

(defn log-file
  "Determine log-file based on --logdir option.
   Leave empty for no log file"
  [opt]
  (when-let [logdir (:logdir opt)]
    (fs/file logdir (format "genie-%s.log" (current-timestamp-file)))))

(defn first-file
  "Find first file according to glob-specs in dir.
   Return nil if none found, a string otherwise. Search in dir (no
  sub-dirs). Check glob-specs seq in order"
  [dir glob-specs]
  (when (and dir (seq glob-specs))
    (if-let [files (seq (fs/glob (fs/file dir) (first glob-specs)))]
      (str (first files))
      (recur dir (rest glob-specs)))))

(defn first-executable
  "Find first file according to glob-specs in dir.
   Return nil if none found, a string otherwise. Search in dir (no
  sub-dirs). Check glob-specs seq in order"
  [dir names]
  (when *verbose*
    (debug "called first-executable with:" dir ", and:" names))
  (when (and dir (seq names))
    (let [file (fs/file dir (first names))]
      (if (fs/executable? file)
        file
        (recur dir (rest names))))))

(defn find-in-path
  "Find executable in system PATH.
   Use fs/exec-paths.
   names is a seq of e.g. [\"java\" \"java.exe\"]"
  ([names]
   (find-in-path names (fs/exec-paths)))
  ([names paths]
   (when (seq paths)
     (if-let [first-exec (first-executable (first paths) names)]
       first-exec
       (recur names (rest paths))))))

(defn first-existing-dir
  "Find first dir in dirs seq that exists and return it.
   Return nil if none found."
  [dirs]
  (when *verbose*
    (debug "called first-existing-dir with:" dirs))
  (when-let [dir (first dirs)]
    (let [dir (expand-home dir)]
      (if (fs/exists? dir)
        (str dir)
        (recur (rest dirs))))))

;; some functions to determine locations of java, genied.jar and config.
(defn java-binary
  "Determine location of java binary.
   By checking in this order:
   - GENIE_JAVA_CMD
   - JAVA_CMD
   - JAVA_HOME
   - java in system PATH"
  [_opt]
  (fs/normalize
   (or (System/getenv "GENIE_JAVA_CMD")
       (System/getenv "JAVA_CMD")
       (when-let [java-home (System/getenv "JAVA_HOME")]
         (first-executable java-home ["java" "java.exe"]))
       (find-in-path ["java" "java.exe"]))))

(defn daemon-dir
  "Determine location of genie daemon dir.
   By checking in this order:
   - daemon in cmdline options
   - GENIE_DAEMON_DIR
   - ~/tools/genie"
  [opt]
  (expand-home
   (or (:daemon opt)
       (System/getenv "GENIE_DAEMON_DIR")
       (first-existing-dir [ "~/tools/genie"]))))

(defn daemon-jar
  "Determine install location of genied jar file
   By checking the dirs as in `daemon-dir`.
   Return nil iff nothing found."
  [opt]
  (when-let [dir (daemon-dir opt)]
    (debug "Found daemon-dir:" dir)
    (let [genied-jar (fs/file dir "genied.jar")]
      (when (fs/exists? genied-jar)
        genied-jar))))

;; use property babashka file instead of *file*, for when this file is
;; included (load-file) by install.clj
(defn source-jar
  "Determine path of source uberjar, iff it exists.
   Return nil otherwise"
  []
  (let [uberjar-dir (fs/file genie-src-root "genied/target/uberjar")]
    (debug "*file* :" *file*)
    (debug "babashka.file property:" (System/getProperty "babashka.file"))
    (debug "genie-src-root: " (str genie-src-root))
    (debug "  uberjar-dir:" uberjar-dir)
    (when (fs/exists? uberjar-dir)
      (first (fs/glob uberjar-dir "*standalone*.jar")))))

(defn bytes->str
  "If `x` is a byte-array, convert it to a string.
   return as-is otherwise.
  copied from babashka: test/babashka/impl/nrepl_server_test.clj"
  [x]
  (if (bytes? x) (String. (bytes x))
      (str x)))

(defn read-msg
  "Convert all byte-arrays in msg to strings.
  copied from babashka: test/babashka/impl/nrepl_server_test.clj"
  [msg]
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

(defn read-bencode
  "Wrapper around b/read-encode and read-msg"
  [in]
  (let [result (read-msg (b/read-bencode in))]
    (debug "<- " result)
    result))

(defn write-bencode
  "Wrapper around b/write-bencode, for verbose logging"
  [out command]
  (debug "-> " command)
  (b/write-bencode out command))

(defn msg-id
  "Create a UUID for adding to nRepl message"
  []
  (str (java.util.UUID/randomUUID)))

(defn println-result
  "For verbose/debug printing of nrepl messages"
  [result]
  (debug "<- " result))

;; TODO - merge with read-print-result.  but take care of recur in
;; combination with both output and input. If this one recurs without
;; printing output, we lose some output.
(defn read-result
  "Read result channel until status is 'done'.
   Return result map with keys :done, :need-input and keys of nrepl-result"
  [in]
  (let [{:keys [status] :as result}
        (read-bencode in)
        need-input (some #{"need-input"} status)
        done (some #{"done"} status)]
    (if-not (or done need-input)
      (recur in)
      (merge {:done done :need-input need-input} result))))

(defn read-print-result
  "Read and print result channel until status is 'done'.
   Return result map with keys :done, :need-input and keys of nrepl-result"
  [in]
  (loop [old-value nil]
    (let [{:keys [out err value ex root-ex status] :as result}
          (read-msg (b/read-bencode in))
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
      (when (and value *verbose*)
        (println "value: " value))
      (when ex
        (println "ex: " ex))
      (when root-ex
        (println "root-ex: " root-ex))
      (if-not (or done need-input)
        (recur (or old-value value))
        (merge {:done done :need-input need-input :value old-value} result)))))

(defn read-lines
  "Read at least one line from reader, possibly more.
   genied 'need-input', so we need at least one line (or char).
   Use .ready to see if we can read more. This should be faster.
   Each line returned ends with a new-line.
   Return one single string, possibly containing multiple lines.
   If input is exhausted, return nil"
  [{:keys [max-lines]} rdr]
  (loop [lines []
         line (.readLine rdr)
         read 1]
    (if line
      (if (and (.ready rdr) (< read max-lines))
        (recur (conj lines (str line "\n"))
               (.readLine rdr)
               (inc read))
        (str/join (conj lines (str line "\n"))))
      (when (seq lines) (str/join lines)))))

(defn connect-nrepl
  "Connect to an nRepl server and open in and out TCP streams"
  [{:keys [port]}]
  (let [s (java.net.Socket. "localhost" port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))]
    {:socket s
     :out out
     :in in}))

(def session-atom
  "Atom to hold the session-id
  For use when the babashka script is killed, and server session
  should be killed as well"
  (atom nil))

(defn exec-expression
  "Return string with expression to execute on Genie daemon.
   Make sure it's a valid string passable through nRepl/bencode"
  [ctx script main-fn script-params]
  (str "(genied.client/exec-script \"" script "\" '" main-fn " " ctx
       " [" script-params "])"))

(defn nrepl-eval
  "Eval a Genie script in a genied/nRepl session"
  [opt {:keys [eval-id] :as ctx} script main-fn script-params]
  (let [{:keys [out in]} (connect-nrepl opt)
        _ (write-bencode out {"op" "clone" "id" (msg-id)})
        session (:new-session (read-result in))
        ctx (assoc ctx :session session)
        expr (exec-expression ctx script main-fn script-params)]
    (try
      (debug "nrepl-eval: " expr)
      (reset! session-atom {:session session :eval-id eval-id :out out :in in})
      (write-bencode out {"op" "eval" "session" session "id" eval-id
                          "code" expr})
      (loop [it 0]
        (let [res (read-print-result in)]
          (when (:need-input res)
            (debug "Need more input!")
            (let [lines (read-lines opt *in*)]
              (write-bencode out {"session" session "id" (msg-id)
                                  "op" "stdin" "stdin" lines})
              (read-result in) ;; read ack
              (recur (inc it))))))
      (catch Exception e
        (warn "Caught exception: " e))
      (finally
        (write-bencode out {"op" "close" "session" session "id" (msg-id)})
        (read-result in)
        (reset! session-atom nil)))))

(defn create-context
  "Create script context, with current working directory (cwd)"
  [opt script]
  (let [script (normalized script)
        cwd (normalized ".")]
    {:cwd (str cwd)
     :client "babashka"
     :script (str script)
     :opt opt
     :client-version "0.1.0"
     :protocol-version "0.1.0"
     :eval-id (msg-id)}))

(defn last-namespace
  "Determine last namespace in the script.
   read ns-decl from the script-file.
   use the last ns-decl in the script.
   If no ns-decl found, return nil"
  [script]
  (debug "Determine main function from script: " script)
  (with-open [rdr (clojure.java.io/reader (fs/file script))]
    (when-let [namespaces
               (seq (for [line (line-seq rdr)
                          :let [[_ ns] (re-find #"^\(ns ([^ \(\)]+)" line)]
                          :when (re-find #"^\(ns " line)]
                      ns))]
      (debug "namespaces found in script: " namespaces)
      (last namespaces))))

(defn det-main-fn
  "Determine main function from the script.
   read ns-decl from the script-file, add '/main'
   use the last ns-decl in the script.
   If no ns-decl found, return `main` (root-ns)"
  [opt script]
  (debug "Determine main function from script: " script)
  (or (:main opt)
      (if-let [namespace (last-namespace script)]
        (str namespace "/main")
        "main")))

(defn normalize-param
  "file normalise a parameter, so the daemon-process can find it.
  Even though it has a different current-working-directory (cwd).
   - if a param starts with -, it's not a relative path.
   - if it starts with /, also not relative
   - if it start with ./, or is a single dot, then it is relative.
     Also useful when a path does start with a '-'
   - if it starts with a letter/digit/underscore, it could be relative.
     Check with `(fs/exists?)` then.
   If --nonormalize given, this conversion is not done."
  [param]
  (let [first-char (first param)]
    (cond (= \- first-char) param
          (= \/ first-char) param
          (= [\. \/] (take 2 param)) (normalized param)
          (= "." param) (normalized param)
          (fs/exists? param) (normalized param)
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
  "Quote parameters in 'cmd-line' with double quotes"
  [params]
  (str/join " " (map quote-param params)))

(defn exec-script
  "Execute given script with opt and script-params"
  [{:keys [nonormalize] :as opt} script script-params]
  (let [ctx (create-context opt script)
        script (normalized script)
        main-fn (det-main-fn opt script)
        script-params (-> script-params
                          (normalize-params nonormalize)
                          quote-params)]
    (nrepl-eval opt ctx script main-fn script-params)))

(defn print-help
  "Print help when --help given, or errors, or no script"
  [{:keys [summary options arguments errors]}]
  (println "genie.clj - Babashka script to run scripts in Genie daemon")
  (println summary)
  (println)
  (println "Current options:" options)
  (println "Current arguments:" arguments)
  (when (empty? arguments)
    (println "  Need a script to execute"))
  (when errors
    (println "Errors:" errors)))

(defn do-admin-command
  "Perform an admin command on daemon.
   Use opened session with socket, in and out streams.
   Reading responses might fail, don't try if no-read given."
  [{:keys [out in]} command
   & [{:keys [no-read]}]]
  (try
    (write-bencode out command)
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
  (if-let [{:keys [session eval-id] :as admin-session} @session-atom]
    (do
      (warn "Shutdown hook triggered, stopping script")
      (do-admin-command admin-session {"op" "interrupt" "session" session
                                       "interrupt-id" eval-id} {:no-read true})
      (debug "Wrote op=interrupt")
      (do-admin-command admin-session {"op" "close" "session" session
                                       "id" (msg-id)} {:no-read true})
      (debug "wrote op=close"))
    (debug "session already closed, do nothing")))

(defn admin-get-sessions
  "Get sessions given a current admin-session"
  [admin-session]
  (let [{:keys [sessions]}
        (do-admin-command admin-session {"op" "ls-sessions"})]
    sessions))

;; use daemon function, to also show other session info such as
;; script, maybe also start-time.
(defn admin-list-sessions
  "List currently open/running sessions/scripts"
  [opt]
  (debug "Connecting to daemon on port:" (:port opt))
  (let [admin-session (connect-nrepl opt)]
    (try
      (debug "Connected, now send list-sessions command")
      (let [sessions
            (-> (do-admin-command admin-session
                                  {"op" "eval"
                                   "code" "(genied.client/list-sessions)"})
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
  [admin-session sessions]
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
        session-specs (split-sessions admin-session sessions)
        sessions (mapcat #(find-session admin-session %) session-specs)]
    (doseq [{:keys [session script]} sessions]
      (println (str "Closing session: [" session "] " script))
      ;; giving both interrupt and close results in error messages and
      ;; the genie.clj client process hanging, as no :done message is
      ;; received. Only interrupt does not work, but only close
      ;; does. So do this for now.
      #_(do-admin-command admin-session {"op" "interrupt" "session" session
                                         "interrupt-id" eval-id})
      (do-admin-command admin-session {"op" "close" "session" session}))))

(defn admin-stop-daemon!
  "Stop the daemon running on given port"
  [opt]
  (let [admin-session (connect-nrepl opt)]
    (do-admin-command admin-session {"op" "eval"
                                     "code" "(genied.client/stop-daemon!)"}
                      {:no-read true}))
  (println "Stopped daemon"))

(defn genied-command
  "Create command to start genied.
   Based on java-bin and genied-jar.
   Or failing that, Leiningen.
   Return vector of command and process options (cwd to use).
   Also set :inherit true to see the daemon starting, but this
   does not seem to work.
   If client is started with verbose, so will daemon.
   Return nil iff no suitable command found"
  [opt java-bin genied-jar]
  ;; fs/exists? throws on nil, so check.
  (cond (and java-bin genied-jar (fs/exists? genied-jar))
        ;; nil/empty options (wrt verbose) should be last, stops named
        ;; parameter processing.
        [[java-bin '-jar genied-jar '-p (:port opt) (when *verbose* '-v)]
         {:dir (str (fs/parent genied-jar))}]
        (find-in-path ["lein"])
        [[(find-in-path ["lein"]) 'run
          '-- (if *verbose* '-v "") '-p (:port opt)]
         {:dir (str (normalized (fs/file *file* ".." ".." "genied")))
          :inherit true}]
        :else
        [nil nil]))

;; TODO - check if process already started. Although starting twice
;; does not seem harmful: (process) returns quickly, old process still
;; running.
(defn admin-start-daemon!
  "Start the daemon process on given port.
   Use environment vars and check directories for locations of java
  binary and genied.jar"
  [opt]
  (println "Starting daemon on port:" (:port opt))
  (when (windows?)
    (println "Running on Windows, starting daemon is tricky."
             "If this fails, try starting the Genie daemon manually.")
    (debug "Running with -v might result in long log-waits in daemon-startup"))
  (let [java-bin (java-binary opt)
        genied-jar (or (daemon-jar opt) (source-jar))
        [command command-opt] (genied-command opt java-bin genied-jar)]
    (if command
      (do
        (debug "java binary:" java-bin)
        (debug "genied-jar:" genied-jar)
        (println "cmd:" (str/join " " command) ", cwd:" (:dir command-opt))
        (let [proc (p/process command command-opt)]
          (println (format "Process started, waiting (max %d sec) %s"
                           (:max-wait-daemon opt)
                           "until port is available"))
          (if-let [res (wait/wait-for-port "localhost" (:port opt)
                                           {:timeout (* 1000 (:max-wait-daemon opt)) :pause 200})]
            (println "Ok, started in" (:took res) "msec")
            (println "Failed to start server, process =" proc))          ))
      (println "No suitable command found to start Genie daemon."
               "Try running with -v"))))

(defn admin-restart-daemon!
  "Restart the daemon process on given port."
  [opt]
  (admin-stop-daemon! opt)
  (println "Wait 3 seconds...")
  (Thread/sleep 3000)
  (admin-start-daemon! opt))

(defn admin-command!
  "Perform an admin command instead of running a script"
  [{:keys [list-sessions kill-sessions start-daemon
           stop-daemon restart-daemon] :as opt} _args]
  (cond list-sessions
        (admin-list-sessions opt)
        kill-sessions
        (admin-kill-sessions opt kill-sessions)
        stop-daemon
        (admin-stop-daemon! opt)
        start-daemon
        (admin-start-daemon! opt)
        restart-daemon
        (admin-restart-daemon! opt)
        :else
        (warn "Unknown admin command: " opt)))

(defn admin-command?
  "Return true if an admin command is given as a cmdline option"
  [{:keys [list-sessions kill-sessions start-daemon
           stop-daemon restart-daemon]}]
  (or list-sessions kill-sessions start-daemon stop-daemon restart-daemon))

(defn print-context
  "Print context vars when verbose is given"
  [opts opt args]
  (when *verbose*
    (debug "*command-line-args* = " *command-line-args*)
    (debug "opts = " opts)
    (debug "opt=" opt ", args=" args)
    (doseq [env-var ["JAVA_HOME" "JAVA_CMD" "GENIE_JAVA_CMD"
                     "GENIE_CLIENT_DIR" "GENIE_CONFIG_DIR" "GENIE_DAEMON_DIR"
                     "GENIE_LOG_DIR" "GENIE_SCRIPTS_DIR" "GENIE_TEMPLATE_DIR" ]]
      (debug "ENV VAR" env-var "=" (System/getenv env-var)))
    (doseq [path (fs/exec-paths)]
      (debug "in PATH:" path))))

(defn main
  "Main function"
  []
  (let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)
        opt (:options opts)
        args (:arguments opts)]
    (binding [*verbose* (-> opts :options :verbose)
              *logfile* (log-file (:options opts))]
      (print-context opts opt args)
      (cond (admin-command? opt)
            (admin-command! opt args)
            (or (:help opt) (:errors opts) (empty? args))
            (print-help opts)
            :else
            (try
              (.addShutdownHook (Runtime/getRuntime) (Thread. kill-script))
              (exec-script opt (first args) (rest args))
              (catch Exception e
                (warn "caught exception: " e))))
      ;; do not print/return the result of the last expression:
      nil)))

;; wrt linting with leiningen/bikeshed etc.
;; see https://book.babashka.org/#main_file
(if (= *file* (System/getProperty "babashka.file"))
  (main)
  (println "Loaded as library:" (str (fs/normalize *file*))))
