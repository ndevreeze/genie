#! /usr/bin/env bb

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
            [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :as p])
  (:import [java.io File]))

(load-file (fs/file (fs/parent *file*) "client/genie.clj"))

(def cli-options
  "Cmdline options"
  [[nil "--daemon DAEMON" "Daemon directory"]
   [nil "--client CLIENT" "Client directory"]
   [nil "--config CONFIG" "Config directory"]
   [nil "--logdir LOGDIR" "Logging dir for daemon and client"]
   [nil "--scripts SCRIPTS" "Scripts directory"]
   [nil "--template TEMPLATE" "Template directory"]
   [nil "--dryrun" "Show what would have been done"]
   [nil "--create-uberjar" "Force (re-)creating uberjar"]
   [nil "--no-create-uberjar" "Do not (re-)create uberjar (TBD)"]
   [nil "--start-on-system-boot" "Install Windows genied.bat in startup folder"]
   ["-p" "--port PORT" "Genie daemon port number (for start-on-system-boot)"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help" "Show help"]])

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
    (let [dir (genie/expand-home dir)]
      (if (dir-writable? dir)
        (str dir)
        (recur (rest dirs))))))

(defn template-dir
  "Determine template directory to use"
  [opt]
  (genie/expand-home
   (cond (:template opt) (:template opt)
         (System/getenv "GENIE_TEMPLATE_DIR") (System/getenv "GENIE_TEMPLATE_DIR")
         (System/getenv "GENIE_CONFIG_DIR")
         (fs/file (System/getenv "GENIE_CONFIG_DIR") "template")
         :else
         "~/.config/genie/template")))

(defn installable-daemon-dir
  "Determine location where daemon can be installed.
   By checking in this order:
   - daemon in cmdline options
   - GENIE_DAEMON_DIR
   - ~/tools/genie"
  [opt]
  (genie/expand-home
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
  (genie/expand-home
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
  (genie/expand-home
   (or (:logdir opt)
       (System/getenv "GENIE_LOG_DIR")
       "~/log")))

(defn config-dir
  "Determine location of config directory
   By checking in this order:
   - config in cmdline options
   - GENIE_CONFIG_DIR
   - ~/bin"
  [opt]
  (genie/expand-home
   (or (:config opt)
       (System/getenv "GENIE_CONFIG_DIR")
       "~/.config/genie")))

(defn scripts-dir
  "Determine location of scripts directory
   By checking in this order:
   - scripts in cmdline options
   - GENIE_SCRIPTS_DIR
   - ~/bin"
  [opt]
  (genie/expand-home
   (or (:scripts opt)
       (System/getenv "GENIE_SCRIPTS_DIR")
       "~/bin")))

(defn installable-daemon-jar
  "Determine installable location of genied jar file
   By checking the dirs as in `installable-daemon-dir`.
   Return nil iff nothing found."
  [opt]
  (when-let [dir (installable-daemon-dir opt)]
    (fs/file dir "genied.jar")))

(defn do-make-uberjar!
  "Make uberjar.
   Checks to see if it's needed are done in make-uberjar.
   Return path of existing or just created uberjar"
  [opt]
  (println "Creating uberjar")
  (when (genie/windows?)
    (println "Running on Windows, starting Leiningen from"
             "Babashka/Genie is tricky."
             "\nIf this fails, try 'cd genied' and 'lein uberjar' manually."))
  (if (:dryrun opt)
    (println "  Dry run")
    (do
      (println "Starting 'lein uberjar' ...")
      (let [proc (p/process ['lein 'uberjar] {:dir "genied"})
            exit-code (:exit (p/check proc))]
        (if (zero? exit-code)
          (println "... Success")
          (println "... Non-zero exit-code:" exit-code)))
      (genie/source-jar))))

(defn install-genied-library!
  "Install library jar into local maven repo."
  [opt]
  (println "Install library to local Maven repo")
  (when (genie/windows?)
    (println "Running on Windows, starting Leiningen from"
             "Babashka/Genie is tricky."
             "\nIf this fails, try 'cd genied' and 'lein uberjar' manually."))
  (if (:dryrun opt)
    (println "  Dry run")
    (do
      (println "Starting 'lein install' ...")
      (let [proc (p/process ['lein 'install] {:dir "genied"})
            exit-code (:exit (p/check proc))]
        (if (zero? exit-code)
          (println "... Success")
          (println "... Non-zero exit-code:" exit-code))))))

(defn make-uberjar
  "Make uberjar iff it does not exist yet, or --force given.
   Return path of existing or just created uberjar.
   Uberjar is removed as side-effect of make install,
   which is also called by this script. So uberjar is always
   made again. Have a --no-create-uberjar param, but need
   to implement this one."
  [opt]
  (if-let [uberjar (genie/source-jar)]
    (if (:create-uberjar opt)
      (do-make-uberjar! opt)
      (do
        (println "Uberjar already created:" (str uberjar))
        uberjar))
    (do-make-uberjar! opt)))

(defn install-file
  "Install a file from source to target
   Create target dirs if needed
   With options:
   :replace - true - copy even if already exists. False - not
   :dryrun - true - show what would be copied
  if either src or dest is nil, don't install anything and print a warning"
  [src dest {:keys [replace dryrun]}]
  (if (or (nil? src) (nil? dest))
    (println "ERROR: Either src or dest is nil, don't copy. src=" src ", dest=" dest)
    (if (or replace (not (fs/exists? dest)))
      (if dryrun
        (println "Dryrun:" (str src) "=>" (str dest))
        (do
          (println "Install:" (str src) "=>" (str dest))
          (fs/create-dirs (fs/parent dest))
          (fs/copy src dest {:replace-existing true})))
      (println "Not copying over existing file:" (str dest)))))

(defn install-daemon
  "Install daemon uberjar and bash script to target location"
  [opt]
  (let [src (make-uberjar opt)
        dest (installable-daemon-jar opt)]
    (if (and src dest)
      (do
        (install-file src dest (merge opt {:replace true}))
        (install-file "genied/genied.sh" (fs/file (fs/parent dest) "genied.sh")
                      (merge opt {:replace true}))
        (install-genied-library! opt))
      (println "ERROR: Either src or dest is nil, don't install. src=" src ", dest=" dest))))

(defn install-clients
  "Install clients to given clients dir"
  [opt]
  (let [target-dir (client-dir opt)]
    (doseq [src (fs/glob (fs/file "client") "genie.*")]
      (install-file src (fs/file target-dir (fs/file-name src))
                    (merge opt {:replace true})))))

(defn install-scripts
  "Install scripts to target location"
  [opt]
  (let [target-dir (scripts-dir opt)]
    (doseq [src (fs/glob (fs/file "scripts") "*.clj")]
      (install-file src (fs/file target-dir (fs/file-name src))
                    (merge opt {:replace true})))))

(defn install-template
  "Install template to target location, iff not already there"
  [opt]
  (let [target-dir (template-dir opt)]
    (doseq [src (fs/glob (fs/file "template") "*")]
      (install-file src (fs/file target-dir (fs/file-name src))
                    (merge opt {:replace false})))))

(defn install-config
  "Install config to target location, iff not already there"
  [opt]
  (let [target-dir (config-dir opt)
        src "genied/genie.edn"]
    (install-file src (fs/file target-dir (fs/file-name src)) (merge opt {:replace false}))))

(defn unix-path
  "Replace backslashes (\\) with forward slashes (/) for putting in bash config"
  [path]
  (str/replace (str path) #"\\" "/"))

(defn windows-path
  "Replace forward slashes (/) with backslashes (\\) for putting in Windows config"
  [path]
  (str/replace (str path) #"/" "\\"))

(defn show-bash-config
  "Show lines to put in .profile or .bashrc"
  [opt]
  (println "\nAdd the following lines to your ~/.profile or ~/.bash_profile:")
  (println (str "export GENIE_CLIENT_DIR=" (unix-path (client-dir opt))))
  (println (str "export GENIE_DAEMON_DIR=" (unix-path (genie/daemon-dir opt))))
  (println (str "export GENIE_JAVA_CMD=" (genie/normalized (genie/java-binary opt))))
  (println (str "export GENIE_CONFIG_DIR=" (unix-path (config-dir opt))))
  (println (str "export GENIE_LOG_DIR=" (unix-path (log-dir opt))))
  (println (str "export GENIE_TEMPLATE_DIR=" (unix-path (template-dir opt))))
  (println (str "export GENIE_SCRIPTS_DIR=" (unix-path (scripts-dir opt))))
  (println (str "alias genie='$GENIE_CLIENT_DIR/genie.clj'"))
  (println (str "alias genie-new='$GENIE_SCRIPTS_DIR/genie_new.clj'")))

(defn show-windows-config
  "Show lines to put in Windows environment (or 4start.bat)"
  [opt]
  (println "\nAdd the following lines to your Windows environment (and/or 4start.bat)")
  (println "Consider 'setx env-var env-value' as well")
  (println (str "set GENIE_CLIENT_DIR=" (windows-path (client-dir opt))))
  (println (str "set GENIE_DAEMON_DIR=" (windows-path (genie/daemon-dir opt))))
  (println (str "set GENIE_JAVA_CMD=" (genie/java-binary opt)))
  (println (str "set GENIE_CONFIG_DIR=" (windows-path (config-dir opt))))
  (println (str "set GENIE_LOG_DIR=" (windows-path (log-dir opt))))
  (println (str "set GENIE_TEMPLATE_DIR=" (windows-path (template-dir opt))))
  (println (str "set GENIE_SCRIPTS_DIR=" (windows-path (scripts-dir opt))))
  (println "\nAnd an alias if possible (e.g. with doskey):")
  (println "genie bb %GENIE_CLIENT_DIR%\\genie.clj")
  (println "doskey genie=\\path\\to\\babashka\\bb.exe %GENIE_CLIENT_DIR%\\genie.clj $*")
  (println "\nSee docs/windows.org for more information and work-arounds"))

(defn show-crontab
  "Show command to add to crontab"
  [opt]
  (println "\nAdd the following to your crontab:")
  (println "@reboot" (unix-path (fs/file (genie/daemon-dir opt) "genied.sh"))))

(defn windows-startup-folder
  "Return Windows user startup folder.
   Return nil iff not found."
  [opt]
  (let [startup-dir
        (fs/normalize (fs/file (System/getenv "APPDATA")
                               "Microsoft/Windows/Start Menu/Programs/Startup"))]
    (when (fs/exists? startup-dir)
      startup-dir)))

(defn genied-bat-contents
  "Return string/line with genied startup command.
   Use found files and given environment vars"
  [opt]
  (let [java-bin (genie/java-binary opt)
        genied-jar (genie/daemon-jar opt)
        [command command-opt] (genie/genied-command opt java-bin genied-jar)]
    (if command
      (str (str/join " " command) "\r\n")
      (println "No suitable command found to start Genie daemon."))))

(defn install-startup-bat
  "Create genied.bat startup script in the Windows user startup folder"
  [opt]
  (let [bat-file (fs/file (windows-startup-folder opt) "genied.bat")
        contents (genied-bat-contents opt)]
    (if (and bat-file contents)
      (do
        (spit bat-file contents)
        (println "\nCreated:" (str bat-file)))
      (do
        (println "An error occured: either batch-file location or contents")
        (println "Location:" (str bat-file))
        (println "Contents:" contents)))))

(defn install
  "Install daemon, client, config, templates"
  [opt]
  (install-daemon opt)
  (install-clients opt)
  (install-config opt)
  (install-template opt)
  (install-scripts opt)
  (show-bash-config opt)
  (show-crontab opt)
  (when (genie/windows?)
    (show-windows-config opt)
    (if (:start-on-system-boot opt)
      (install-startup-bat opt)
      (println "\nConsider --start-on-system-boot to create a genied.bat"
               "in your Windows startup folder"))))

(defn print-help
  "Print help when --help given, or errors"
  [{:keys [summary options arguments errors]}]
  (println "install.clj - Babashka script to install Genie:
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
    (binding [genie/*verbose* (-> opts :options :verbose)]
      (if (or (:help opt) (:errors opts))
        (print-help opts)
        (install opt)))))

(if (= *file* (System/getProperty "babashka.file"))
  (main)
  (println "Loaded as library:" (str (fs/normalize *file*))))
