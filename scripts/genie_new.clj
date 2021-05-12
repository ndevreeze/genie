#! /usr/bin/env genie.clj

(ns genie-new
  "Create a new Genie script"
  (:require
   [clojure.string :as str]
   [me.raynes.fs :as fs]
   [ndevreeze.logger :as log]
   [ndevreeze.cmdline :as cl]))

;; TODO - add deps.edn-template to use as a param.

(def cli-options
  "Command line options for creating new Genie script"
  [["-n" "--namespace NAMESPACE" "Namespace, by default determined from script"]
   ["-d" "--directory DIRECTORY" "genie template directory"]
   ["-t" "--template TEMPLATE" "template file to use, within template dir"
    :default "template.clj"]
   ["-f" "--force" "Overwrite existing script, if it exists"]
   ["-v" "--verbose" "Verbose/debug logging"]
   ["-h" "--help" "Show this help"]])

(defn dash->underscore
  "Replace dashes in path to underscores.
   Only in the final part (the filename), not the parent-directory."
  [path]
  (let [filename (fs/base-name path)
        dir (str/replace path filename "")
        ;; cannot use fs/parent here, will add current dir and backslashes.
        filename2 (str/replace filename "-" "_")]
    (str dir filename2)))

(defn underscore->dash
  "Replace underscores in path to dashes, for namespace"
  [path]
  (str/replace path "_" "-"))

(defn expand-home
  "fs/expand-home does not work correctly on Windows with paths with slashes"
  [path]
  (fs/expand-home (str/replace path "/" (System/getProperty "file.separator"))))

(defn template-dir
  "Determine template directory to use"
  [opt]
  (log/debug "GENIE_TEMPLATE_DIR:" (System/getenv "GENIE_TEMPLATE_DIR"))
  (log/debug "GENIE_CONFIG_DIR:" (System/getenv "GENIE_CONFIG_DIR"))
  (log/debug "~/.config/genie/template expanded: "
             (expand-home "~/.config/genie/template"))
  (expand-home
   (or (:directory opt)
       (System/getenv "GENIE_TEMPLATE_DIR")
       (when (System/getenv "GENIE_CONFIG_DIR")
         (fs/file (System/getenv "GENIE_CONFIG_DIR") "template"))
       "~/.config/genie/template")))

(defn det-full-ns
  "Determine full namespace string based on base-namespace and script or
  explicit namespace if given.
  Do not use :base-namespace now, this conflicts with calling from clj
  instead of genie"
  [opt script]
  (or (:namespace opt)
      (underscore->dash (fs/base-name script true))))

;; could use leiningen/renderer here, as used in e.g. app-default template.
;; but a simple string/regexp replace should work here.
(defn create-from-template
  "Create target file from template and some vars"
  [template target {:keys [namespace script]}]
  (let [contents (-> (slurp template)
                     (str/replace "{{namespace}}" namespace)
                     (str/replace "{{script}}" script))]
    (fs/mkdir (fs/parent target)) ;; for when creating a new dir as well.
    (spit target contents)))

(defn create-script
  "Create a script based on template in genie-project directory.
   param script - fully qualified, expanded"
  [opt script]
  (when (fs/exists? script)
    (if (:force opt)
      (fs/delete script)
      (println "Already exists and no --force given: " script)))

  ;; if target still exists here, do nothing.
  (when-not (fs/exists? script)
    (let [full-ns (det-full-ns opt script)
          dir (template-dir opt)]
      (println "Full-ns: " full-ns)
      (create-from-template (fs/file dir (:template opt))
                            script
                            {:namespace full-ns
                             :script (str/replace (str script) "\\" "/")})
      (fs/chmod "+x" script)
      (let [deps-target (fs/file (fs/parent script) "deps.edn")]
        (if (fs/exists? deps-target)
          (println "deps.edn already exists, do not overwrite")
          (fs/copy (fs/file dir "deps.edn") deps-target)))
      (println "Created:" (str (fs/normalized script))))))

(defn add-dot-clj
  "Add .clj to path if it's not there yet"
  [path]
  (if (= (fs/extension path) ".clj")
    path
    (str path ".clj")))

(defn make-absolute-clj-script
  "Make sure path is absolute, using :cwd in ctx if needed.
   Also add .clj extension if needed.  And replace dashes by
  underscores where needed (only in final parts of path, not a common
  base part like dev-user"
  [ctx path]
  (let [path (-> path add-dot-clj dash->underscore)]
    (if (fs/absolute? path)
      path
      (fs/file (:cwd ctx) path))))

(defn script
  "Create a new genie script.
  Arguments contains the script(s) to be created as a relative path."
  [opt arguments ctx]
  (log/init {:location :home :name "genie-new" :cwd (:cwd ctx)
             :level (if (:verbose opt) :debug :info)})
  (log/debug "ctx:" ctx)
  (doseq [script arguments]
    (create-script opt (make-absolute-clj-script ctx script))))

(defn main
  "Main function as called from Genie"
  [ctx args]
  (cl/check-and-exec "" cli-options script args ctx))
