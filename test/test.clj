#! /usr/bin/env genie

;; 2020-12-31: does not work directly on MacOS: ./test.clj gives bad
;; interpreter errors. Several different errors, depending on the
;; first line.  what works for now is doing 'genie test.clj'

(ns test
  "Test several specific things related stdout/err, logfiles."
  (:require [ndevreeze.cmdline :as cl]
            [me.raynes.fs :as fs]
            [ndevreeze.logger :as log]))

(def cli-options
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]
   ["-a" "--all" "Run all tests (default is minimal, only println to stdout"]
   ["-l" "--log" "Test logging"]
   ["-s" "--stream" "Test streaming stdout"]
   ["-w" "--cwd" "Show working directory"]
   ["-f" "--file FILE" "Read and show file"]
   ["-e" "--error" "Test stderr"]
   ["-i" "--input" "test stdin"]
   [nil "--cmdlineparams" "test command line parameters"]])

(defn println-stdout
  [opt ctx arguments]
  (println "Just a simple line to stdout"))

(defn logging-home
  "Using home-dir as log location"
  [opt ctx arguments]
  (println "Test logging to home-dir")
  (log/init {:location :home :name "test"})
  (log/info "8 + 8 = " (+ 8 8))
  (log/info "config: " (:config opt))
  (log/info "Opt: " (str opt))
  (println "End of test logging"))

(defn logging-cwd
  [opt ctx arguments]
  (println "Test logging to current-dir")
  (log/init {:location :cwd :name "test" :cwd (:cwd ctx)})
  (log/info "8 + 8 = " (+ 8 8))
  (log/info "config: " (:config opt))
  (log/info "Opt: " (str opt))
  (println "End of test logging"))

(defn logging
  [opt ctx arguments]
  (logging-home opt ctx arguments)
  (logging-cwd opt ctx arguments))

(defn read-file
  [{:keys [file] :as opt} ctx arguments]
  (if file
    (let [file2 (str (fs/absolute file))]
      (if (fs/exists? file2)
        (do
          (println "Contents of file:" file2)
          (println (slurp file2))
          (println "======================")
          (println "That was:" file2))
        (println "File does not exist:" file2)))
    (println "No file parameter given, not reading file")))

(defn working-dir
  [opt ctx arguments]
  (println "Working dir (user.dir): " (System/getProperty "user.dir")))

(defn standard-error
  "Print something to stderr"
  [opt ctx arguments]
  (println "Next line to stderr:")
  (binding [*out* *err*]
    (println "Hello, STDERR!"))
  (println "Stdout again"))

(defn standard-input
  "Read from stdin and print to stdout"
  [opt ctx arguments]
  (println "Stdin:")
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (println line))
  (println "End of stdin"))

(defn streaming-stdout
  [opt ctx arguments]
  (println "Wait 3 seconds...")
  (Thread/sleep 3000)
  (println "Done waiting"))

(defn command-line-params
  [opt ctx arguments]
  (println "Command line parameters:")
  (println "opt: " opt)
  (println "ctx: " ctx)
  (println "arguments: " arguments)
  (println "#arguments: " (count arguments))
  )

(defn test?
  [opt arg]
  (or (:all opt) (opt arg)))

(defn maybe-test
  [opt ctx arguments arg f]
  (when (test? opt arg)
    (f opt ctx arguments)))

(defn script [opt arguments ctx]
  (println-stdout opt ctx arguments)
  (println "ctx: " ctx)
  (maybe-test opt ctx arguments :file read-file)
  (maybe-test opt ctx arguments :log logging)
  (maybe-test opt ctx arguments :stream streaming-stdout)
  (maybe-test opt ctx arguments :cwd working-dir)
  (maybe-test opt ctx arguments :error standard-error)
  (maybe-test opt ctx arguments :cmdlineparams command-line-params))

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
