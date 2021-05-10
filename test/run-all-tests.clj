#! /usr/bin/env bb

(ns run-all-tests
  "Run all tests in this directory"
  (:require [babashka.process :as p]
            [clojure.tools.cli :as cli]
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
  `user.home`."
  [path]
  (let [path (str path)]
    (if (.startsWith path "~")
      (let [sep (.indexOf path File/separator)]
        (if (neg? sep)
          (home (subs path 1))
          (io/file (home (subs path 1 sep)) (subs path (inc sep)))))
      (io/file path))))

(defn normalized
  "From Raynes/fs, combination of absolutize and normalize"
  [path]
  (fs/normalize (fs/absolutize path)))

(def cli-options
  "Genie client command line options"
  [["-p" "--port PORT" "Genie daemon port number for test"
    :default 7887
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-l" "--logdir LOGDIR" "Directory for client log. Empty: no logging"]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help" "Show help"]
   [nil "--no-start-stop-daemon" "Do not start a daemon before the tests"]])

(def ^:dynamic *verbose*
  "Dynamic var, set to true when -verbose cmdline option given.
   Used by function `debug` below"
  false)

(def ^:dynamic *logfile*
  "Dynamic var, set to a logfile name when logs should be saved in a file.
   Used by function `log` below"
  nil)

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

(defn genie-clj
  "Determine normalized path to genie.clj script"
  [opt]
  (normalized (fs/file *file* "../../client/genie.clj")))

(defn start-daemon
  "Start daemon using genie.clj client"
  [opt]
  (let [p (p/process ["bb" (genie-clj opt) "--start-daemon" "-p" (:port opt)])]
    (info "Started daemon process, waiting to be ready...")
    (info "Result of waiting: " @p ", " p)))

(defn stop-daemon
  "Stop daemon using genie.clj client"
  [opt]
  (let [p (p/process ["bb" (genie-clj opt) "--stop-daemon" "-p" (:port opt)])]
    (Thread/sleep 500)
    (info "Stopped daemon process...")
    (info "Result of waiting: " @p ", " p)))

(defn test-dir
  "Determine normalized path of test-dir"
  [opt]
  (normalized (fs/parent *file*)))

(defn run-test
  [{:keys [port] :as opt} script cmdline-opts]
  (let [proc (p/process (concat ["bb" (genie-clj opt) "-p" port script] cmdline-opts)
                        {:in "foo" :out :string :err :string})]
    (info "Started test: " script ", with options: " (str/join " " cmdline-opts))
    (deref proc)
    (info (str "stdout result:\n" (deref (:out proc))))
    (info "---------")
    (info (str "stderr result:\n" (deref (:err proc))))
    (info "==========================")))

(defn run-script?
  "return true iff script should be run"
  [script]
  (cond (re-find #"run-all-tests" script) false
        (re-find #"bb_" script) false
        (re-find #"_lib.clj" script) false
        :else true))

(defn in-test-dir
  "Convert test-script.clj file to normalized path in the test-dir"
  [opt script-name]
  (fs/file (test-dir opt) script-name))

(defn run-all-tests
  "Run all tests in this dir.
   Possibly start/stop daemon around the tests"
  [{:keys [no-start-stop-daemon] :as opt}]
  (when-not no-start-stop-daemon
    (start-daemon opt))

  (doseq [script (->> (fs/glob (test-dir opt) "*.clj")
                      (map str)
                      (filter run-script?))]
    (run-test opt script []))

  (run-test opt (in-test-dir opt "test.clj") ["-a"])
  (run-test opt (in-test-dir opt "test_params.clj") ["a" "b" "third" "4"])
  ;;test_stdin
  ;;(run-test opt (in-test-dir opt "test_head.clj") ["test_head.clj"])
  (run-test opt (in-test-dir opt "test_head.clj") [(in-test-dir opt "test_head.clj")])

  (run-test opt (in-test-dir opt "test_add_numbers.clj") ["1" "2" "3"])

  (fs/delete-if-exists "test_write_file.out")
  (run-test opt (in-test-dir opt "test_write_file.clj") ["--file" "./test_write_file.out" "--delete-file"])

  (run-test opt (in-test-dir opt "test_stdin.clj") ["--stdin"])

  (when-not no-start-stop-daemon
    (stop-daemon opt)))

(defn print-help
  "Print help when --help given, or errors, or no script"
  [{:keys [summary options arguments errors]}]
  (println "run-all-tests.clj - run all genie tests in this directory")
  (println summary)
  (println)
  (println "Current options:" options)
  (println "Current arguments:" arguments)
  (when errors
    (println "Errors:" errors)))

(defn main
  "Main function"
  []
  (let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)
        opt (:options opts)
        args (:arguments opts)]
    (debug "*command-line-args* = " *command-line-args*)
    (debug "opts = " opts)
    (debug "opt=" opt ", args=" args)
    (if (or (:help opt) (:errors opts))
      (print-help opts)
      (run-all-tests opt))))

;; wrt linting with leiningen/bikeshed etc.
;; see https://book.babashka.org/#main_file
(if (= *file* (System/getProperty "babashka.file"))
  (main)
  (println "Not called/sourced as main, do nothing"))
