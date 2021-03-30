#! /usr/bin/env genie

;; test stdin, also with redirection:
;; * read as contents from file: cat file | genie test_stdin.clj
;; * read as output from another process: ls | genie test_stdin.clj
;; * read as a stream from another process: long-proc | grenie test_stdin.clj

(ns test-stdin
  (:require [ndevreeze.cmdline :as cl]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [ndevreeze.logger :as log]
            [clojure.string :as str]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"]
   ["-f" "--file FILE" "Output file"]
   ["-h" "--help" "Show this help"]])

(defn standard-error
  "Print something to stdout and stderr"
  [opt ctx arguments]
  (println "Next line to stderr (this line on stdout):")
  (binding [*out* *err*]
    (println "Hello, STDERR!"))
  (println "Stdout again"))

(defn read-standard-input
  "Read standard input and output each line in capitals."
  [opt ctx arguments]
  (println "Start reading stdin")
  (doseq [line (line-seq (io/reader *in*))]
    (println (str/upper-case line)))
  (println "Finished reading stdin"))

(defn read-standard-input2
  "Read standard input and output each line in capitals.
   Use a test string binding here."
  [opt ctx arguments]
  (println "Start reading stdin")
  (fs/delete (:file opt))
  (with-in-str "line 1\nline 2\n" 
    (doseq [line (line-seq (io/reader *in*))]
      (spit (:file opt) (str line "\n") :append true)
      (println (str/upper-case line))))
  (println "Finished reading stdin"))

(defn read-standard-input3
  "Read standard input and output each line in capitals to file and stdout.
   Also write to a file, using spit and append."
  [opt ctx arguments]
  (println "Start reading stdin")
  (fs/delete (:file opt))
  (spit (:file opt) "Start reading stdin (spit)\n" :append true)
  (doseq [line (line-seq (io/reader *in*))]
    (spit (:file opt) (str (str/upper-case line) "\n") :append true)
    (println (str/upper-case line))
    (flush)
    (Thread/sleep 1000))
  (spit (:file opt) "Finished reading stdin (spit)\n" :append true)
  (println "Finished reading stdin"))

(defn script [opt arguments ctx]
  #_(read-standard-input2 opt ctx arguments)
  (read-standard-input3 opt ctx arguments)
  #_(read-standard-input opt ctx arguments))

;; expect context/ctx now as first parameter, a map.
(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test'
(defn -main
  "Entry point from clj cmdline script.
   Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
