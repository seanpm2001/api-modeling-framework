(ns api-modelling-framework.build
  (:require [clojure.java.shell :as jsh]
            [clojure.string :as string]
            [cheshire.core :as json]))

;; Project information
(defn project-info [] (-> "project.clj" slurp read-string))
(defn find-project-info [kw] (->> (project-info) (drop-while #(not= % kw)) second))

(def version  (-> (project-info) (nth 2) (string/split #"-") first))
(def project  (-> (project-info) (nth 1) str))
(def description (find-project-info :description))
(def keywords ["raml" "open-api" "swagger" "rdf" "shacl" "api" "modelling"])
(def license  (-> (find-project-info :license) :name))
(def repository "https://github.com/mulesoft-labs/api-modelling-framework")
(def npm-dependencies (->> (find-project-info :npm) :dependencies (map (fn [[n v]] [(str n) (str v)]))(into {})))


;; Packages
(defn npm-package []
  {:name project
   :description description
   :version version
   :main "index"
   :license license
   :repository repository
   :dependencies npm-dependencies})

;; Commands
(defn sh! [& args]
  (println "==> " (string/join " " args))
  (let [{:keys [err out exit]} (apply jsh/sh args)]
    (if (not= exit 0)
      (throw (Exception. err))
      (clojure.string/split-lines out))))

(defn mkdir [path]
  (sh! "mkdir" "-p" path))

(defn rm [path]
  (sh! "rm" "-rf" path))

(defn cljsbuild [target]
  (sh! "lein" "cljsbuild" "once" target))

(defn clean []
  (sh! "lein" "clean"))

(defn cp [from to]
  (sh! "cp" "-rf" from to))

(defn pwd [] (first (sh! "pwd")))

(defn ln [source target]
  (sh! "ln" "-s" (str (pwd) source) (str (pwd) target)))

(defn cd [to]
  (sh! "cd" to))

(defn npm-link [path from]
  (sh! "mkdir" "-p" (str from "/node_modules"))
  (sh! "ln" "-s" (str (pwd) "/" path) (str from "/node_modules/api-modelling-framework")))

(defn npm-install [path]
  (sh! "npm" "--prefix" path "install"))

(defn local-gulp [from] (str from "/node_modules/.bin/gulp"))

(defn tsc [bin project]
  (sh! bin "-p" project))

(defn local-tsc [from] (str from "/node_modules/.bin/tsc"))

(defn gulp-serve [from]
  (sh! (local-gulp from) "--cwd" from "serve"))

(defmacro no-error [form]
  `(try
     ~form
     (catch Exception ex# nil)))

;; builds

(defn build [target]
  (println "* Cleaning output directory")
  (clean)
  (rm "target")
  (rm (str "output/" target))

  (println "* Recreating output directory")
  (mkdir "output")

  (println "* Building " target)
  (cljsbuild target
)
  (println "* Copying license")
  (cp "LICENSE" (str "output/" target "/LICENSE")))

;; CLI

(defn build-node []
  (println "** Building Target: node\n")
  (build "node")
  (cp "js" "output/node/js")

  (println "* copy package index file")
  (cp "build/package_files/index.js" "output/node/index.js")

  (println "generating npm package")
  (-> (npm-package)
      (json/generate-string {:pretty true})
      (->> (spit "output/node/package.json")))
  (cp "js" "output/node/js")
  ;; this index file is generated by cljs but is
  ;; not right, paths are wrong and we need some
  ;; other initialisation, we provide our own
  ;; copied from package_files/index.js
  (rm "output/node/js/amf.js"))


(defn build-web []
  (println "** Building Target: web\n")
  (build "web"))

(defn run-api-modeller []
  (build-node)
  (let [api-modeller (str (pwd) "/api-modeller")]
    (println "linking project to api-modeller")
    (npm-link "output/node" api-modeller)

    (println "Installing npm dependencies")
    (npm-install api-modeller)

    (println "Compiling typescript")
    (no-error (tsc (local-tsc api-modeller) api-modeller))
    (gulp-serve api-modeller)))

(defn run-api-web []
  (build-web)

  (let [api-modeller-web (str (pwd) "/api-modeller-web")]
    (cp "output/web/amf.js" (str api-modeller-web "/public/js/"))
    (npm-install api-modeller-web)
    (gulp-serve api-modeller-web)))


(defn -main [& args]
  (try
    (condp = (first args)
      "web"              (build-web)
      "node"             (build-node)
      "api-modeller"     (run-api-modeller)
      "api-modeller-web" (run-api-web)
      (do (println "Unknown task")
          (System/exit 2)))
    (catch Exception ex
      (println "Error building project")
      (prn ex)
      (System/exit 1)))
  (System/exit 0))
