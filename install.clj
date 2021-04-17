#! /usr/bin/env bb

;; TODO other locations needed on Windows and Mac?

(ns install
  "Installer for genie.
   Locations are determined like this:
   - explicity given on cmdline
   - already defined in env-vars
   - some default locations:
     - daemon in ~/tools/genie (root not supported)
     - client in ~/bin
     - config in ~/.config/genie
     - template in ~/.config/genie/template"
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [babashka.process :as p])
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

(def cli-options
  "Cmdline options"
  [[nil "--daemon DAEMON" "Daemon directory"]
   [nil "--client CLIENT" "Client directory"]
   [nil "--config CONFIG" "Config directory"]
   [nil "--logdir LOGDIR" "Logging dir for daemon and client"]
   [nil "--scripts SCRIPTS" "Scripts directory"]
   [nil "--template TEMPLATE" "Template directory"]
   [nil "--dryrun" "Show what would have been done"]
   [nil "--force" "Force re-creating uberjar"]
   ["-h" "--help"]])

(defn error
  "Throw an exception with msg given"
  [msg]
  (throw (Exception. msg)))

(defn user
  "Return current user according to env.
   Check USER and USERNAME"
  []
  (or (System/getenv "USER")
      (System/getenv "USERNAME")
      (error "Cannot determine current user")))

#_(defn root?
    "Return true if current used is root.
   Wrt install-locations"
    []
    (= (user) "root"))

(defn dir-writable?
  "Return true iff dir already exists and can be written into,
   or if its parent is writable
   Assume if we can create a dir, we can also write into it.
   Also assume that going up to a parent, it will eventually exist"
  [dir]
  (if (fs/exists? dir)
    (fs/writable? dir)
    (let [parent (fs/parent dir)]
      (if (= dir parent) ;; check if parent is not the same as dir.
        false
        (dir-writable? parent)))))

(defn first-creatable-dir
  "Find first dir in dirs seq that exists and return it.
   Return nil if none found."
  [dirs]
  (when-let [dir (first dirs)]
    (let [dir (expand-home dir)]
      (if (dir-writable? dir)
        (str dir)
        (recur (rest dirs))))))

(defn template-dir
  "Determine template directory to use"
  [opt]
  (expand-home
   (cond (:template opt) (:template opt)
         (System/getenv "GENIE_TEMPLATE_DIR") (System/getenv "GENIE_TEMPLATE_DIR")
         (System/getenv "GENIE_CONFIG_DIR")
         (fs/file (System/getenv "GENIE_CONFIG_DIR") "template")
         :else
         "~/.config/genie/template")))

;; these functions are the same in genie.clj, so could put in include/library.
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
       (first-creatable-dir [ "~/tools/genie"]))))

(defn client-dir
  "Determine location of client directory
   By checking in this order:
   - client in cmdline options
   - GENIE_CLIENT_DIR
   - ~/bin"
  [opt]
  (expand-home
   (or (:client opt)
       (System/getenv "GENIE_CLIENT_DIR")
       "~/bin")))

(defn log-dir
  "Determine location of log directory
   By checking in this order:
   - logdir in cmdline options
   - GENIE_LOG_DIR
   - ~/log"
  [opt]
  (expand-home
   (or (:logdir opt)
       (System/getenv "GENIE_LOG_DIR")
       "~/log")))

(defn config-dir
  "Determine location of client directory
   By checking in this order:
   - config in cmdline options
   - GENIE_CLIENT_DIR
   - ~/bin"
  [opt]
  (expand-home
   (or (:config opt)
       (System/getenv "GENIE_CONFIG_DIR")
       "~/.config/genie")))

(defn scripts-dir
  "Determine location of client directory
   By checking in this order:
   - scripts in cmdline options
   - GENIE_CLIENT_DIR
   - ~/bin"
  [opt]
  (expand-home
   (or (:scripts opt)
       (System/getenv "GENIE_SCRIPTS_DIR")
       "~/bin")))

;; rename, so same name as in genie.clj
(defn daemon-jar
  "Determine install location of genied jar file
   By checking the dirs as in `daemon-dir`.
   Return nil iff nothing found."
  [opt]
  (fs/file (daemon-dir opt) "genied.jar"))

(defn source-jar
  "Determine path of source uberjar, iff it exists.
   Return nil otherwise"
  []
  (first (fs/glob (fs/file "genied/target/uberjar") "*standalone*.jar")))

;; TODO - use --force.
(defn make-uberjar
  "Make uberjar iff it does not exist yet, or --force given.
   Return path of existing or just created uberjar"
  [opt]
  (if-let [uberjar (source-jar)]
    (do
      (println "Uberjar already created:" (str uberjar))
      uberjar)
    (do
      (println "Creating uberjar")
      (if (:dryrun opt)
        (println "  Dry run")
        (do
          (println "Starting 'lein uberjar' ...")
          (let [proc (p/process ['lein 'uberjar] {:dir "genied"})
                exit-code (:exit (p/check proc))]
            (if (zero? exit-code)
              (println "... Success")
              (println "... Non-zero exit-code:" exit-code)))
          (source-jar))))))

(defn install-file
  "Install a file from source to target
   Create target dirs if needed
   With options:
   :force - true - copy even if already exists. False - not
   :dryrun - true - show what would be copied"
  [src dest {:keys [force dryrun]}]
  (when (or force (not (fs/exists? dest)))
    (if dryrun
      (println "Dryrun:" (str src) "=>" (str dest))
      (do
        (println "Install:" (str src) "=>" (str dest))
        (fs/create-dirs (fs/parent dest))
        (fs/copy src dest {:replace-existing true})))))

(defn install-daemon
  "Install daemon uberjar and bash script to target location"
  [opt]
  (let [src (make-uberjar opt)
        dest (daemon-jar opt)]
    (install-file src dest (merge opt {:force true}))
    (install-file "genied/genied.sh" (fs/file (fs/parent dest) "genied.sh")
                  (merge opt {:force true}))))

(defn install-clients
  "Install clients to given clients dir"
  [opt]
  (let [target-dir (client-dir opt)]
    (doseq [src (fs/glob (fs/file "client") "genie.*")]
      (install-file src (fs/file target-dir (fs/file-name src))
                    (merge opt {:force true})))))

(defn install-scripts
  "Install scripts to target location"
  [opt]
  (let [target-dir (scripts-dir opt)]
    (doseq [src (fs/glob (fs/file "scripts") "*.clj")]
      (install-file src (fs/file target-dir (fs/file-name src))
                    (merge opt {:force true})))))

(defn install-template
  "Install template to target location, iff not already there"
  [opt]
  (let [target-dir (template-dir opt)]
    (doseq [src (fs/glob (fs/file "template") "*")]
      (install-file src (fs/file target-dir (fs/file-name src))
                    (merge opt {:force false})))))

(defn install-config
  "Install config to target location, iff not already there"
  [opt]
  (let [target-dir (config-dir opt)
        src "genied/genie.edn"]
    (install-file src (fs/file target-dir (fs/file-name src)) (merge opt {:force false}))))

(defn show-bash-config
  "Show lines to put in .profile or .bashrc"
  [opt]
  (println "Add the following lines to your ~/.profile:")
  (println (str "export GENIE_CLIENT_DIR=" (client-dir opt)))
  (println (str "export GENIE_DAEMON_DIR=" (daemon-dir opt)))
  (println (str "export GENIE_JAVA_CMD=java"))
  (println (str "export GENIE_CONFIG_DIR=" (config-dir opt)))
  (println (str "export GENIE_LOG_DIR=" (log-dir opt)))
  (println (str "export GENIE_TEMPLATE_DIR=" (template-dir opt)))
  (println (str "export GENIE_SCRIPTS_DIR=" (scripts-dir opt))))

(defn show-crontab
  "Show command to add to crontab"
  [opt]
  (println "Add the following to your crontab:")
  (println "@reboot" (str (fs/file (daemon-dir opt) "genied.sh"))))

(defn install
  "Install daemon, client, config, templates"
  [opt]
  (install-daemon opt)
  (install-clients opt)
  (install-config opt)
  (install-template opt)
  (install-scripts opt)
  (show-bash-config opt)
  (show-crontab opt))

(defn print-help
  "Print help when --help given, or errors"
  [{:keys [summary options arguments errors]}]
  (println "install.clj - babashka script to install genie:
  daemon, client, config, scripts and template")
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
        opt (:options opts)]
    (if (or (:help opt) (:errors opts))
      (print-help opts)
      (install opt))))

(if (= *file* (System/getProperty "babashka.file"))
  (main)
  (println "Not called/sourced as main, do nothing"))
