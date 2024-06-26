(ns genied.classloader
  "Classloader functions.
   Mostly related to dynamic class loaders and the system root loader."
  (:gen-class)
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [genied.diagnostics :as diag]
            [genied.state :as state]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]))

;; 2021-09-23: copied from client.clj, should be in separate namespace.
(defn log-daemon-debug
  "Log a debug message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :debug forms))

(defn log-daemon-info
  "Log a info message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :info forms))

(defn log-daemon-warn
  "Log a warn message to the original logger and *err* stream"
  [& forms]
  (log/log (log/get-logger (:err (state/get-out-streams))) :warn forms))

;; 2021-03-13: this one from discussion in clojure threads.
;; 2021-05-23: not used currently, maybe remove. It was given as an example.
(defn ensure-dynamic-classloader
  "Ensure the current thread has a Clojure DynamicClassLoader.
   Return the classloader after possibly changing it."
  []
  (let [thread (Thread/currentThread)
        cl (.getContextClassLoader thread)]
    (when-not (instance? clojure.lang.DynamicClassLoader cl)
      (.setContextClassLoader thread (clojure.lang.DynamicClassLoader. cl)))
    (.getContextClassLoader thread)))

(defn bind-root-loader
  "Bind Compiler/LOADER to a new one.
   This stays fixed in server and later client calls.
  Use (baseloader) as parent.  Return newly created classloader"
  []
  (let [bl (clojure.lang.RT/baseLoader)
        cl (clojure.lang.DynamicClassLoader. bl)]
    (log/debug "bind-root-loader, baseLoader = " bl ", new cl = " cl)
    (.bindRoot clojure.lang.Compiler/LOADER cl)
    ;; also set thread binding, if applicable
    (if-let [box (.getThreadBinding clojure.lang.Compiler/LOADER)]
      (do
        (log/debug "result of getThreadBinding: " box)
        (if (.isBound clojure.lang.Compiler/LOADER)
          (.set clojure.lang.Compiler/LOADER cl)
          (log/debug "Compiler/LOADER is not bound, do not .set it")))
      (log/debug "Compiler/LOADER has no threadBinding yet, not using .set"))
    (log/debug "After bindRoot, Compiler/LOADER (.deref) = "
               (.deref Compiler/LOADER))
    (log/debug "After bindRoot, baseloader = " (clojure.lang.RT/baseLoader))
    cl))

(defn init-dynamic-classloader!
  "Ensure the system/server has a dynamic classloader.
   And keep it in an atom, so clients may use it. This version uses
  the (dynamic) classloader of ndevreeze.cmdline/check-and-exec"
  []
  (state/set-classloader! (bind-root-loader)))

(defn set-dynamic-classloader!
  "Set global classloader on current (client) thread.
   And also on the loader, if this is possible.
   Return the set classloader"
  []
  (let [cl (state/get-classloader)
        thread (Thread/currentThread)]
    (log/debug "set-dynamic-classloader! with logging, state/get-cl:" cl)
    (diag/print-baseloader-classloaders "dyn3 - 1:")
    (.setContextClassLoader thread cl)
    (diag/print-baseloader-classloaders "dyn3 - 2:")
    (.bindRoot clojure.lang.Compiler/LOADER cl)
    (diag/print-baseloader-classloaders "dyn3 - 3:")
    (.set clojure.lang.Compiler/LOADER cl)
    (diag/print-baseloader-classloaders "dyn3 - 4:")
    cl))

;; TODO - make given repo's more flexible - allow all/other specs
;; similar to deps.edn.
;; 2021-02-28: current Pomegranate seems the only working version;
;; Cider/nrepl and tools.deps versions don't seem to work currently.
(defn load-library
  "Dynamically load a library, using Pomegranate for now.
   Use global classloader as set in init-dynamic-classloader!
  lib - symbol, eg 'ndevreeze/logger
  version - string, eg \"0.2.0\""
  ([lib version]
   (load-library lib version (state/get-classloader)))
  ([lib version classloader]
   (log-daemon-debug "Loading library: " lib ", version: " version)
   (log-daemon-debug "Using classloader: " classloader)
   (try
     (if-let [ver (state/dep-version lib)]
       (log-daemon-info (format "Lib (%s) already loaded with version: %s" lib ver))
       (log-daemon-info (format "Lib (%s) not loaded yet" lib)))
     (let [coord [lib version]]
       (if (state/has-dep? coord)
         (log-daemon-debug "Already loaded: " coord)
         ;; 2024-04-02: Always load lib if requested version != loaded version.
         (let [res (pom/add-dependencies
                    :classloader classloader
                    :coordinates [coord]
                    :repositories (merge
                                   cemerick.pomegranate.aether/maven-central
                                   {"clojars" "https://clojars.org/repo"}))]
           (state/add-dep! coord)
           (log/info (str "Loaded library: " lib ", version: " version))
           (log-daemon-debug "Result of add-dependencies: " res)
           res)))
     (catch Exception e
       (log-daemon-warn "Exception during load-library: " e)))))

(def project-libraries
  "Same list as in project.clj"
  '[[org.clojure/clojure "1.11.1"]
    [org.clojure/tools.cli "1.0.214"]
    [clj-commons/fs "1.6.310"]
    [nrepl "1.0.0"]
    [clj-commons/pomegranate "1.2.23"]
    [org.apache.httpcomponents/httpclient "4.5.14"]
    [org.apache.httpcomponents/httpcore "4.4.16"]
    [org.slf4j/slf4j-nop "2.0.6"]
    [org.jsoup/jsoup "1.15.3"]
    [ndevreeze/logger "0.6.1"]
    [ndevreeze/cmdline "0.2.0"]
    [org.tcrawley/dynapath "1.1.0"]
    [commons-codec "1.15"]])

(defn mark-project-libraries
  "Mark libraries in project.clj as loaded.
   So they won't be loaded again, either from server or client/script."
  [{:keys [mark] :as opt}]
  ;; copied from project.clj - how to keep in sync? -> have sync-script, not ideal.
  ;; iff mark!=source, assume none for now, see if that works.
  (if (= mark "source")
    (doseq [coord project-libraries]
      (log/info (str "Mark as loaded from project.clj: " coord))
      (state/add-dep! coord))
    (log-daemon-info (format "mark != source given, assume none, do not mark libraries: %s" mark))))

;; TODO - support other (non-maven) coordinates?
(defn load-libraries
  "Load libraries from a deps.edn file.
   Either at daemon start time or at script exec time"
  [opt]
  (doseq [[lib coordinates] (seq (:deps opt))]
    (load-library lib (:mvn/version coordinates))))

(defn load-startup-libraries
  "Load libraries as given in daemon startup config"
  [opt]
  ;; 2024-04-02: Change logging to info, always interesting.
  (log-daemon-info "Loading startup libraries from:" opt)
  (load-libraries opt)
  (log-daemon-info "Loaded startup libraries from:" opt))

(defn det-deps-edn
  "Determine deps.edn file based on script and ctx.
   Also check parent dir of script, when default src-dir is used"
  [{:keys [deps]} script]
  (cond deps deps
        (fs/exists? (fs/file (fs/parent script) "deps.edn"))
        (fs/file (fs/parent script) "deps.edn")
        (fs/exists? (fs/file (fs/parent (fs/parent script)) "deps.edn"))
        (fs/file (fs/parent (fs/parent script)) "deps.edn")))

(defn load-script-libraries
  "Load libraries as found in deps.edn in script-dir or ctx"
  [ctx script]
  (let [deps-edn (det-deps-edn ctx script)]
    (if (fs/exists? deps-edn)
      (let [script-opt (edn/read-string (slurp deps-edn))]
        (log-daemon-info "Loading libraries from:" deps-edn)
        (log-daemon-debug "#libs in deps.edn:" (count (:deps script-opt)))
        (load-libraries script-opt)
        (log-daemon-info "Loaded libraries from:" deps-edn))
      ;; 2024-04-02: Change log-level to warning if deps.edn not found for script.
      (log-daemon-warn "deps.edn not found for:" script
                       ", deps-edn:" deps-edn))
    deps-edn))
