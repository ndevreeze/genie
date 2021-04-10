(ns genied.diagnostics
  "Classloaders are tricky in Java and Clojure.
  This namespace provides some diagnostic functions. These should
  only be called when *verbose* is true."
  (:gen-class)
  (:require [cemerick.pomegranate :as pom]
            [genied.state :as state]
            [ndevreeze.logger :as log]))

(def ^:dynamic *verbose* "Iff true, call diagnostics for classloaders" false)

(defn- print-classloader-hierarchy
  "Use doseq to really print the lazy seq"
  [tip]
  (doseq [loader (pom/classloader-hierarchy tip)]
    (log/debug loader))
  (log/debug "-----")  )

(defn- print-classpath
  "Use doseq to really print the lazy seq"
  [note tip]
  (log/debug "Classpath of: " note ":")
  (let [hier (pom/classloader-hierarchy tip)]
    (doseq [cl hier]
      (log/debug "in classpath of: " cl ":")
      (doseq [part (pom/get-classpath [cl])]
        (when (re-find #"clj-http|tools\.cli" (str part))
          (log/debug "in classpath of: " cl ":" part)))))
  (log/debug "-----"))

;; From RT.Java (clojure source):
;; static public ClassLoader baseLoader(){
;;   if(Compiler.LOADER.isBound())
;;      return (ClassLoader) Compiler.LOADER.deref();
;;   else if(booleanCast(USE_CONTEXT_CLASSLOADER.deref()))
;;      return Thread.currentThread().getContextClassLoader();
;;   return Compiler.class.getClassLoader();
;; }

;; this one also called from classloader.clj, so check *verbose*
(defn print-baseloader-classloaders
  "From source in RT.baseLoader(), one of the options is chosen"
  ([note]
   (when *verbose*
     (log/debug note)
     (if (.isBound Compiler/LOADER)
       (log/debug "1. Compiler/LOADER: " (.deref Compiler/LOADER))
       (log/debug "1. Compiler/LOADER is not bound"))
     (log/debug "2a. Value of clojure.core/*use-context-classloader*: "
                clojure.core/*use-context-classloader*)
     (log/debug "2b. Context classloader: "
                (.. Thread currentThread getContextClassLoader))
     (log/debug "3. Classloader of Compiler: " (.getClassLoader Compiler))
     (log/debug "Resulting in Baseloader (used by require/load): "
                (clojure.lang.RT/baseLoader))))
  ([]
   (print-baseloader-classloaders "BaseLoader:")))

(defn- print-loaded-libs
  "Use loaded-libs function to determine if a few libs are loaded"
  []
  (log/debug "part of (loaded-libs):")
  (let [libs (->> (loaded-libs)
                  (map str)
                  (filter #(re-find #"clj-http|tools\.cli" %)))]
    (doseq [lib libs]
      (log/debug lib))))

(defn print-diagnostic-info
  "Print things like classloader-hierarchy and current thread and session"
  [label]
  (when *verbose*
    (log/debug "Diagnostic info for: " label)
    (log/debug "Classloader hierarchy for current thread:")
    (print-classloader-hierarchy
     (.. Thread currentThread getContextClassLoader))
    (log/debug "Classloader hierarchy for base classloader:")
    (print-classloader-hierarchy (clojure.lang.RT/baseLoader))
    (print-classpath "thread" (.. Thread currentThread getContextClassLoader))
    (print-classpath "base" (clojure.lang.RT/baseLoader))
    (log/debug "Current thread: " (Thread/currentThread))
    (print-loaded-libs)
    (print-baseloader-classloaders)
    (log/debug "Server dynamic classloader: " (state/get-classloader))
    (log/debug "======================")))
