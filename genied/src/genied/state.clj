(ns genied.state
  "Keep state of the daemon proces.
   Classloader for all libraries, loaded dependencies/libs,
   output/error streams, loaded scripts and sessions.
   Keep some singleton atoms."
  (:gen-class))

;; only define the singleton classloader here, for use from other namespaces.
;; dependencies is a set of coordinates, where coordinate is a vec of lib and version.
(def classloader (atom {:loader nil :dependencies #{}}))

(defn get-classloader
  "Get the globally set dynamic classloader"
  []
  (:loader @classloader))

(defn set-classloader!
  "Set the globally set dynamic classloader
   reset set of loaded packages"
  [new-val]
  (reset! classloader {:loader new-val :dependencies #{}}))

(defn has-dep?
  "Return true if dependency is already loaded"
  [coord]
  (contains? (:dependencies @classloader) coord))

(defn add-dep!
  "Add dependency coordinates to loaded set"
  [coord]
  (swap! classloader update-in [:dependencies] conj coord))

;; not directly related to classloaders, but singletons as well, so keep here for now.
;; keep system *out* and *err*, so they can be distinguished from the dynamic nRepl ones.
(def out-streams (atom {:out nil :err nil}))

(defn set-out-streams!
  "set output streams to current values in atom out-streams"
  [out err]
  (reset! out-streams {:out out :err err}))

(defn get-out-streams
  "Get map with system out and err streams"
  []
  @out-streams)
