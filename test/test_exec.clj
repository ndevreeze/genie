#! /usr/bin/env genie.clj

;; 2022-02-19: Issue that main Genied process is stopped after some
;; time after running this script. Might have something to do if
;; script generates errors, but also seems to fail after just a few
;; good runs, after about 3 minutes, 1 minute is still ok.

(ns test-exec
  "test exec with both a binary and a shell script"
  (:require [ndevreeze.cmdline :as cl]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [me.raynes.conch :as sh]
            [me.raynes.conch.low-level :as shl]
            [me.raynes.fs :as fs]))

(def cli-options
  "Default cmdline options"
  [["-c" "--config CONFIG" "Config file"]
   ["-h" "--help" "Show this help"]])

(defn exec-pwd1
  "Execute pwd, check result"
  []
  (println "Executing pwd1")
  (sh/with-programs [pwd]
    (let [res (pwd)]
      (println "result:" res))    ))

(defn exec-pwd2
  "Execute pwd with low-level proc, check result"
  []
  (println "Executing pwd2")
  (let [proc (shl/proc "pwd")
        res (shl/stream-to-string proc :out)]
    (println "result:" res)))

#_(defn exec-grep-stderr
    "Execute grep with low-level proc, check stderr result"
    []
    (println "Executing grep")
    (let [proc (shl/proc "/bin/grep" "testtest" "/this/does/not/exist")
          res (shl/stream-to-string proc :err)]
      (println "stderr result:" res)))

(defn exec-grep-stderr
  "Execute grep with low-level proc, check stderr result"
  []
  (println "Executing grep")
  (let [proc (shl/proc "grep" "testtest" "/this/does/not/exist")
        res (shl/stream-to-string proc :err)]
    (println "stderr result:" res)))

;; 2022-02-19: using high-level conch seems to cause the Genie daemon
;; to crash, maybe something with Threads and Futures. Added default
;; uncaught exception handler, but does not seem to help.
(defn exec-echo-script
  "Execute test-echo.sh in the same dir, with let-programs, check result"
  [{:keys [script]}]
  (println "Executing test-echo.sh, calling from script:" script)
  (sh/let-programs [test-echo (str (fs/file (fs/parent script) "test-echo.sh"))]
                   (let [res (test-echo "par1" "par2" 3)]
                     (println "result:" res))))

(defn exec-echo-script-error
  "Execute test-echo.sh in the same dir, with let-programs, check result"
  [{:keys [script]}]
  (println "Executing test-echo2.sh (expect ERROR, does not exist), calling from script:" script)
  (sh/let-programs [test-echo (str (fs/file (fs/parent script) "test-echo2.sh"))]
                   (let [res (test-echo "par1" "par2" 3)]
                     (println "result:" res))))

(defn script
  "Script called by both `main` and `-main`"
  [opt arguments ctx]
  (exec-pwd1)
  (exec-pwd2)
  (exec-grep-stderr)
  (exec-echo-script ctx)
  (exec-echo-script-error ctx))

(defn main
  "Main from genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))

;; for use with 'clj -m test'
(defn -main
  "Entry point from clj cmdline script.
   Need to call System/exit, hangs otherwise."
  [& args]
  (cl/check-and-exec "" cli-options script args {:cwd "."})
  (System/exit 0))
