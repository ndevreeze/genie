#! /usr/bin/env bb

;; for using raynes.fs (clj-commons/fs):
;; export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

(ns genie
  (:require [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [me.raynes.fs :as fs]))

(def cli-options
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-h" "--help"]])


;; from https://book.babashka.org/#_interacting_with_an_nrepl_server
(defn nrepl-eval [port expr]
  (let [s (java.net.Socket. "localhost" port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "eval" "code" expr})
        bytes (get (b/read-bencode in) "value")]
    (String. bytes)))

(defn main
  "Main function"
  [opt args]
  (let [port (:port opt)]
    (println "args: " args)
    (nrepl-eval port "(+ 1 2 3)"))) ;; => "6")

(let [opts (cli/parse-opts *command-line-args* cli-options)]
  (main (:options opts) (:arguments opts)))
  



