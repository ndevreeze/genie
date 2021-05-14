#! /usr/bin/env genie.clj

(ns sync-project-libraries
  "Check if dependencies in project.clj are in sync
   with the ones in classloader.clj/project-libraries"
  (:require 
   [clojure.edn :as edn]
   [clojure.java.io :as io]            
   [clojure.set :as set]
   [me.raynes.fs :as fs]
   [ndevreeze.cmdline :as cl]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"
    :default "FILL IN"]
   ["-p" "--project PROJECT" "Genied project with project.clj"]
   ["-v" "--verbose" "Enable verbose/debug logging"]
   ["-h" "--help" "Show this help"]])

;; from https://stackoverflow.com/questions/15234880/how-to-use-clojure-edn-read-to-
;; get-a-sequence-of-objects-in-a-file
(defn edn-seq
  "Returns the objects from stream as a lazy sequence."
  ([]
   (edn-seq *in*))
  ([stream]
   (edn-seq {} stream))
  ([opts stream]
   (lazy-seq (cons (clojure.edn/read opts stream) (edn-seq opts stream)))))

;; from https://stackoverflow.com/questions/15234880/how-to-use-clojure-edn-read-to-
;; get-a-sequence-of-objects-in-a-file
(defn swallow-eof
  "Ignore an EOF exception raised when consuming seq."
  [seq]
  (-> (try
        (cons (first seq) (swallow-eof (rest seq)))
        (catch java.lang.RuntimeException e
          (when-not (= (.getMessage e) "EOF while reading")
            (throw e))))
      lazy-seq))

(defn edn-read-file
  "Read Clojure source as edn, one item per top-level item"
  [path]
  (with-open [stream (java.io.PushbackReader. (clojure.java.io/reader path))]
    (doall (swallow-eof (edn-seq stream)))))

(defn read-prj-deps
  "Read dependencies from Leiningen project.clj file.
   Coerce into a map, dropping first 3 items"
  [path]
  (let [prj-edn (edn/read-string (slurp path))]
    (:dependencies (apply hash-map (drop 3 prj-edn)))))

(defn read-mark-deps
  "Read dependencies from (def project-libraries) in classloader.clj"
  [path]
  (let [cl-edn-seq (edn-read-file path)
        mark (filter #(= (second %) 'project-libraries) cl-edn-seq)]
    (nth (first mark) 4)))

(defn only-in-first
  "Return seq of elements only in first collection coerced to set,
   and not in the second"
  [seq1 seq2]
  (seq (set/difference (set seq1) (set seq2))))

(defn print-diffs
  "Print differences between 2 dependency lists"
  [prj-deps mark-deps]
  (println "Warning - project.clj deps list differs from one in classloader.clj")
  (println "Only in project.clj:")
  (doseq [coord (only-in-first prj-deps mark-deps)]
    (println coord))
  (println "Only in classloader.clj:")
  (doseq [coord (only-in-first mark-deps prj-deps)]
    (println coord)))

(defn script [opt _arguments _ctx]
  (if (:project opt)
    (do
      (println "project to read: " (str (fs/normalized (:project opt))))
      (let [prj-deps (read-prj-deps (fs/file (:project opt) "project.clj"))
            mark-deps (read-mark-deps (fs/file (:project opt) "src/genied/classloader.clj"))]
        (println "prj-deps: " prj-deps)
        (println "mark-deps: " mark-deps)
        (if (= prj-deps mark-deps)
          (println "Ok, both dependency lists are the same")
          (print-diffs prj-deps mark-deps))))
    (println "--project is mandatory (genied directory)")))

(defn main [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; call with: 'clj -m sync-project-libraries'
(defn -main
  "Entry point from clj cmdline script. Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
