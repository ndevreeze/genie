(ns genied.sing-loader
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
