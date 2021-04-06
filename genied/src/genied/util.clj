(ns genied.util
  (:gen-class)
  (:require [nrepl.core :as repl]))

(defn repl-eval
  "Another test function"
  [ses code]
  (doall (repl/message ses {:op "eval" :code code})))
