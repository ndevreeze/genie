#! /usr/bin/env bb

;; for using raynes.fs (clj-commons/fs):
;; export BABASHKA_CLASSPATH=$(clojure -Spath -Sdeps '{:deps {clj-commons/fs {:mvn/version "1.6.307"}}}')

;; TODO:

;; * logging: own lib or something babashka specific? or a simple log
;;   function with 2 levels and one *verbose* option.

(ns genie
  (:require [bencode.core :as b]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            ;;            [nrepl.core :as nrepl]
            ))

(def cli-options
  [["-p" "--port PORT" "Genie daemon port number"
    :default 7888
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-v" "--verbose" "Verbose output"]
   ["-h" "--help"]])

(defn read-result
  "Read result channel until status is 'done'"
  [in]
  (let [result (b/read-bencode in)
        out-bytes (get result "out")
        status (get result "status")
        done (and status
                  (= "done" (String. (first status))))]
    (when out-bytes
      (print (String. out-bytes)))
    #_(cond val-bytes (String. val-bytes)
            result (str "Complete result: " result)
            :else "No result in value field and complete result")
    (when (not done)
      (recur in))))

;; from https://book.babashka.org/#_interacting_with_an_nrepl_server
(defn nrepl-eval [host port expr]
  (let [s (java.net.Socket. host port)
        out (.getOutputStream s)
        in (java.io.PushbackInputStream. (.getInputStream s))
        _ (b/write-bencode out {"op" "clone"})
        _ (read-result in)
        _ (b/write-bencode out {"op" "eval" "code" expr})]
    (read-result in)))

#_(defn nrepl-eval [port expr]
    (let [s (java.net.Socket. "localhost" port)
          out (.getOutputStream s)
          in (java.io.PushbackInputStream. (.getInputStream s))
          _ (b/write-bencode out {"op" "eval" "code" expr})
          result (b/read-bencode in)
          bytes (get result "value")
          out-bytes (get result "out")]
      (when out-bytes
        (println "out-bytes:" (String. out-bytes)))
      (cond bytes (String. bytes)
            result (str "Complete result: " result)
            :else "No result in value field and complete result")))

(defn create-context
  [opt script]
  {})

(defn det-main-fn
  [opt script]
  "test-dyn-cl/main")

;;   set clj_commands "(genied.client/exec-script \"$script2\" '$main_fn $ctx \[$script_params\])"
(defn exec-expression
  [ctx script main-fn script-params]
  (str "(genied.client/exec-script \"" script "\" '" main-fn " " ctx " [" script-params "])"))

;; TODO
(defn normalize-params
  [params]
  params)

;; TODO - check if number, maybe not needed.
(defn quote-param
  [param]
  (str "\"" param "\""))

(defn quote-params
  [params]
  (str/join " " (map quote-param params)))

;; some poor man's logging for now
(defn info
  "Log always"
  [& msg]
  (println (str/join " " msg)))

(defn debug
  "Log if verbose is set"
  [& msg]
  (println (str/join " " msg)))

;; TODO - check how babashka returns result. It could/should also redirect stdin/out/err.
(defn exec-script
  "Execute given script with opt and script-params"
  [{:keys [port verbose] :as opt} script script-params]
  (let [ctx (create-context opt script)
        script2 (fs/normalized script)
        main-fn (det-main-fn opt script2)
        script-params2 (-> script-params normalize-params quote-params)
        expr (exec-expression ctx script2 main-fn script-params2)]
    (nrepl-eval "localhost" port expr)))

(defn main
  "Main function"
  [opt args]
  (exec-script opt (first args) (rest args)))

(let [opts (cli/parse-opts *command-line-args* cli-options)]
  (main (:options opts) (:arguments opts)))
  



