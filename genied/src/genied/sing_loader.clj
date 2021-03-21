(ns genied.sing-loader
  (:gen-class))

;; only define the singleton classloader here, for use from other namespaces.
(def classloader (atom nil))

(defn get-classloader
  "Get the globally set dynamic classloader"
  []
  @classloader)

(defn set-classloader!
  "Set the globally set dynamic classloader"
  [new-val]
  (reset! classloader new-val))
