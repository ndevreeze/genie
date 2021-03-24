#! /usr/bin/env bb

;; for using raynes.fs (clj-commons/fs):
;; export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

(ns genie
  (:require [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [me.raynes.fs :as fs]))

(def cli-options
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-m" "--main MAIN" "Main function to call, namespaced. Default empty: get from script ns-decl"]
   [nil "--nocheckserver" "Do not perform server checks when an error occurs"]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(def ^:dynamic *verbose*
  "Dynamic var, set to true when -verbose cmdline option given.
   Used by function `debug` below"
  false)

;; some poor man's logging for now
(defn info
  "Log always"
  [& msg]
  (println (str/join " " msg)))

(defn debug
  "Log if -verbose is given"
  [& msg]
  (when *verbose*
    (println (str/join " " msg))))

(defn read-print-result
  "Read and print result channel until status is 'done'"
  [in]
  (let [result (b/read-bencode in)
        out-bytes (get result "out")
        err-bytes (get result "err")
        status (get result "status")
        done (and status
                  (= "done" (String. (first status))))]
    (when out-bytes
      (print (String. out-bytes))
      (flush))
    (when err-bytes
      (binding [*out* *err*]
        (print (String. err-bytes))
        (flush)))
    (when (not done)
      (recur in))))

;; from https://book.babashka.org/#_interacting_with_an_nrepl_server
(defn nrepl-eval [host port expr]
  (let [s (java.net.Socket. host port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "clone"})
        _ (read-print-result in) ;; should save session-id for later cancellation.
        _ (b/write-bencode out {"op" "eval" "code" expr})]
    (read-print-result in)))

(defn create-context
  "Create script context, with current working directory (cwd)"
  [opt script]
  (let [script (fs/normalized script)
        cwd (fs/normalized ".")]
    {:cwd (str cwd)
     :script (str script)
     :opt opt}))

;; also test when no namespace is given, root or user namespace
;; then. And does prb not work with clj itself.
(defn det-main-fn
  "Determine main function from the script.
   read ns-decl from the script-file, add '/main'
   use the last ns-decl in the script."
  [opt script]
  (or (:main opt)
      (with-open [rdr (clojure.java.io/reader script)]
        (if-let [namespaces
                 (seq (for [line (line-seq rdr)
                            :let [[_ ns] (re-find #"^\(ns ([^ \(\)]+)" line)]
                            :when (re-find #"^\(ns " line)]
                        ns))]
          (str (last namespaces) "/main")
          "main"))))

(defn exec-expression
  [ctx script main-fn script-params]
  (str "(genied.client/exec-script \"" script "\" '" main-fn " " ctx " [" script-params "])"))

;; TODO
(defn normalize-params
  [params]
  params)

(defn quote-param
  "Quote a parameter with double quotes, for calling exec-script"
  [param]
  (str "\"" param "\""))

(defn quote-params
  [params]
  (str/join " " (map quote-param params)))

(defn exec-script
  "Execute given script with opt and script-params"
  [{:keys [port verbose] :as opt} script script-params]
  (let [ctx (create-context opt script)
        script2 (fs/normalized script)
        main-fn (det-main-fn opt script2)
        script-params2 (-> script-params normalize-params quote-params)
        expr (exec-expression ctx script2 main-fn script-params2)]
    (debug "nrepl-eval: " expr)
    (nrepl-eval "localhost" port expr)))

(defn main
  "Main function"
  [opt args]
  (debug "main, opt=" opt ", args=" args)
  (exec-script opt (first args) (rest args)))

(let [opts (cli/parse-opts *command-line-args* cli-options :in-order true)]
  (binding [*verbose* (-> opts :options :verbose)]
    (debug "*command-line-args* = " *command-line-args*)
    (debug "opts = " opts)
    (main (:options opts) (:arguments opts))))
  



