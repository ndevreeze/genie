#! /usr/bin/env bb

;; TODO other locations needed on Windows and Mac?

(ns install
  "Installer for genie.
   Locations are determined like this:
   - explicity given on cmdline
   - already defined in env-vars
   - some default locations:
     - daemon in /opt/genie or /usr/local/lib/genie (when root)
       or ~/tools/genie (when not root)
     - client in ~/bin
     - config in ~/.config/genie
     - template in ~/.config/genie/template"
  (:require [clojure.tools.cli :as cli]
            [me.raynes.fs :as fs]
            [babashka.process :as p]))

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

(defn root?
  "Return true if current used is root.
   Wrt install-locations"
  []
  (= (user) "root"))

(defn dir-writeable?
  "Return true iff dir already exists and can be written into,
   or if its parent is writeable
   Assume if we can create a dir, we can also write into it."
  [dir]
  (or (and (fs/exists? dir) (fs/writeable? dir))
      (and (not (fs/exists? dir)) (fs/writeable? (fs/parent dir)))))

(defn first-creatable-dir
  "Find first dir in dirs seq that exists and return it.
   Return nil if none found."
  [dirs]
  (when-let [dir (first dirs)]
    (let [dir (fs/expand-home dir)]
      (if (dir-writeable? dir)
        (str dir)
        (recur (rest dirs))))))

(defn template-dir
  "Determine template directory to use"
  [opt]
  (fs/expand-home
   (cond (:template opt) (:template opt)
         (System/getenv "GENIE_TEMPLATE") (System/getenv "GENIE_TEMPLATE")
         (System/getenv "GENIE_CONFIG")
         (fs/file (System/getenv "GENIE_CONFIG") "template")
         :else
         "~/.config/genie/template")))

;; these functions are the same in genie.clj, so could put in include/library.
(defn daemon-dir
  "Determine location of genie home
   By checking in this order:
   - daemon in cmdline options
   - GENIE_DAEMON
   - /opt/genie
   - /usr/local/lib/genie
   - ~/tools/genie"
  [opt]
  (fs/expand-home
   (or (:daemon opt)
       (System/getenv "GENIE_DAEMON")
       (first-creatable-dir ["/opt/genie" "/usr/local/lib/genie" "~/tools/genie"]))))

(defn client-dir
  "Determine location of client directory
   By checking in this order:
   - client in cmdline options
   - GENIE_CLIENT
   - ~/bin"
  [opt]
  (fs/expand-home
   (or (:client opt)
       (System/getenv "GENIE_CLIENT")
       "~/bin")))

(defn log-dir
  "Determine location of log directory
   By checking in this order:
   - logdir in cmdline options
   - GENIE_LOGDIR
   - ~/log"
  [opt]
  (fs/expand-home
   (or (:client opt)
       (System/getenv "GENIE_CLIENT")
       "~/log")))

(defn config-dir
  "Determine location of client directory
   By checking in this order:
   - config in cmdline options
   - GENIE_CLIENT
   - ~/bin"
  [opt]
  (fs/expand-home
   (or (:config opt)
       (System/getenv "GENIE_CONFIG")
       "~/.config/genie")))

(defn scripts-dir
  "Determine location of client directory
   By checking in this order:
   - scripts in cmdline options
   - GENIE_CLIENT
   - ~/bin"
  [opt]
  (fs/expand-home
   (or (:scripts opt)
       (System/getenv "GENIE_SCRIPTS")
       "~/bin")))

(defn genied-jar-file
  "Determine install location of genied jar file
   By checking the dirs as in `genie-home`.
   Return nil iff nothing found."
  [opt]
  (or (System/getenv "GENIE_JAR")
      (fs/file (daemon-dir opt) "genied.jar")))

(defn genied-source-jar
  "Determine path of source uberjar, iff it exists.
   Return nil otherwise"
  []
  (when-let [uberjars (seq (fs/glob (fs/file "genied/target/uberjar") "*standalone*.jar"))]
    (first uberjars)))

;; TODO - use --force.
(defn make-uberjar
  "Make uberjar iff it does not exist yet, or --force given.
   Return path of existing or just created uberjar"
  [opt]
  (if-let [uberjar (genied-source-jar)]
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
          (genied-source-jar))))))

(defn install-daemon
  "Install daemon uberjar and bash script to target location"
  [opt]
  (let [src (make-uberjar opt)
        dest (genied-jar-file opt)]
    (println "Installing uberjar from:" (str src) "=>" (str dest))
    (if (:dryrun opt)
      (println "  Dry run")
      (do
        (if (fs/copy src dest)
          (println "Ok, copied uberjar")
          (println "Copy failed, is destination in use? [uberjar]"))
        (if (fs/copy "genied/genied.sh" (fs/file (fs/parent dest) "genied.sh"))
          (println "Ok, copied genied.sh")
          (println "Copy failed, is destination in use? [genied.sh]"))))))

(defn install-clients
  "Install clients to given clients dir"
  [opt]
  (let [target-dir (client-dir opt)]
    (println "Installing clients from 'client' => " (str target-dir))
    (if (:dryrun opt)
      (println "  Dryrun")
      (doseq [src (fs/glob (fs/file "client") "genie.*")]
        (fs/copy src (fs/file target-dir (fs/base-name src))))))  )

(defn install-scripts
  "Install scripts to target location"
  [opt]
  (let [target-dir (scripts-dir opt)]
    (println "Installing from 'scripts' => " (str target-dir))
    (if (:dryrun opt)
      (println "  Dryrun")
      (doseq [src (fs/glob (fs/file "scripts") "*.clj")]
        (fs/copy src (fs/file target-dir (fs/base-name src)))))))

(defn install-template
  "Install template to target location, iff not already there"
  [opt]
  (let [target-dir (template-dir opt)]
    (println "Installing from 'template' => " (str target-dir))
    (if (:dryrun opt)
      (println "  Dryrun")
      (doseq [src (fs/glob (fs/file "template") "*")]
        (when-not (fs/exists? (fs/file target-dir (fs/base-name src)))
          (fs/copy src (fs/file target-dir (fs/base-name src)))))))  )

(defn install-config
  "Install config to target location, iff not already there"
  [opt]
  (let [target-dir (config-dir opt)
        src "genie.edn"]
    (println "Installing from 'genied/genie.edn' => " (str target-dir))
    (if (:dryrun opt)
      (println "  Dryrun")
      (when-not (fs/exists? (fs/file target-dir src))
        (fs/copy src (fs/file target-dir src))))))

(defn show-bash-config
  "Show lines to put in .profile or .bashrc"
  [opt]
  (println "Add the following lines to your ~/.profile:")
  (println (str "export GENIE_CLIENT=" (client-dir opt)))
  (println (str "export GENIE_DAEMON=" (daemon-dir opt)))
  (println (str "export GENIE_JAR=" (genied-jar-file opt)))
  (println (str "export GENIE_JAVA_CMD=java"))
  (println (str "export GENIE_CONFIG=" (config-dir opt)))
  (println (str "export GENIE_LOGDIR=" (log-dir opt)))
  (println (str "export GENIE_TEMPLATE=" (template-dir opt)))
  (println (str "export GENIE_SCRIPTS=" (scripts-dir opt))))

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
