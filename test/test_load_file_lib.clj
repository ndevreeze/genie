(ns test-load-file-lib
  "test loading a library from a genie script"
  (:require
   [clojure.data.csv :as csv]))

(defn data-csv
  "Parse some csv in text"
  [opt ctx]
  (println "Parsing csv using data.csv: " (csv/read-csv "load-file,lib-ns")))
