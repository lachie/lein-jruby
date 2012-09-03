(ns leiningen.jruby
  (:use [clojure.java.io :only (file)])
  (:require [lancet.core :as lancet]
            [leiningen.classpath :as classpath]
            [leiningen.core.eval :as ev]
            [clojure.string :as str])
  (:import [org.jruby Main RubyInstanceConfig]
           [org.apache.tools.ant.types Path]
           [org.apache.tools.ant.types Environment$Variable]
           [org.apache.tools.ant ExitException]))


(def default-options
  {:mode "1.9"
   :bundler-version "1.2.0"})


(defn- opts [project]
    (merge default-options (:jruby-options project)))


(def gem-dir ".lein-gems")


(defn- bundler-version
  [project]
  (:bundler-version (opts project)))


(def rubygems-gem-path (str gem-dir "/gems"))
(def rubygems-bin-path (str rubygems-gem-path "/bin"))
(def bundler-gem-path (str gem-dir "/bundled"))
(def bundler-binstubs-path (str gem-dir "/binstubs"))


(defn- source-path
  [project]
  (:jruby-source-path project "src/jruby"))


; here an ant task is set up to call jruby in a subprocess.
; This allows us to manipulate environment variables such as GEM_HOME and PATH
(defn- task-props [project] {:classname "org.jruby.Main"})


(.addTaskDefinition lancet/ant-project "java" org.apache.tools.ant.taskdefs.Java)


(defn- create-jruby-task
  [project args & {:keys [cwd]}]

  (let [url-classpath (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
        cp (str/join java.io.File/pathSeparatorChar (map #(.getPath %) url-classpath))
        task (doto (lancet/instantiate-task lancet/ant-project "java"
                                              (task-props project))
                  (.setClasspath (Path. lancet/ant-project (classpath/get-classpath-string project))))]

    ;(prn "cp" cp)
    ;(prn "pcp" (classpath/get-classpath-string project))

    ; copy args into the task
    (doseq [arg args] (.setValue (.createArg task) arg))

    (.setFailonerror task true)

    ; fork, unless irb
    (.setFork task (not (= "irb" (first args))))

    ; set cwd if we have it
    (when-let [cwd-file (file cwd)] 
      (.setDir task cwd-file))

    task))


(defn- set-env-var
  [task name value]
  (let [envvar (new Environment$Variable)]
    (.setKey envvar (.toUpperCase name)) 
    (.setValue envvar (str value))
    (.addEnv task envvar)))


(defn- prepend-path
  [task paths]
  (let [existing-path (System/getenv "PATH")
        updated-path (str/join ":" (flatten [paths existing-path]))]
    (set-env-var task "PATH" updated-path)))


(defn- process-args
  [project lumpy-args & {:keys [cwd] :as options}]
  (let [include-arg (when cwd (format "-I%s" cwd))
        mode-arg (format "--%s" (:mode (opts project)))
        classpath-arg (str "-J-cp" (classpath/get-classpath-string project))]
    ; XXX set --rubygems?

  (into-array String (flatten [classpath-arg include-arg mode-arg lumpy-args]))))


(defn- jruby-exec
  [project & lumpy-args]
  (let [source-path (source-path project)
        full-source-path (.getAbsolutePath (file source-path))
        args (process-args project lumpy-args :cwd full-source-path)
        task (create-jruby-task project args :cwd full-source-path)
        root (:root project)]

    (println "exec: jruby" (str/join " " (seq args)))

    (prepend-path task [(str root "/" rubygems-bin-path) (str root "/" bundler-binstubs-path)])

    (set-env-var task "GEM_PATH" (str root "/" rubygems-gem-path))
    (set-env-var task "GEM_HOME" (str root "/" rubygems-gem-path))

    (.execute task)))


(defn- any-starts-with?
  [prefix strs]
  (some (fn [str] (.startsWith str prefix)) strs))


(defn- ensure-gem-dir 
  [project]
  (.mkdir (file (:root project) gem-dir)))


(defn- ensure-gem
  [project gemspec]
  (ensure-gem-dir project)
  (let [[gem version] (cond (string? gemspec) [gemspec nil]
                            :else gemspec)
        version-string (when version (format "-v%s" version))]
  (jruby-exec project "-S" "maybe_install_gems" gem version-string)))


(defn- ensure-bundler
  [project]
  (ensure-gem-dir project)
  ; jruby-openssl is often required for bundler, but won't install using this method
  ; install it by hand first.
  ; (ensure-gem project "jruby-openssl")
  (ensure-gem project ["bundler" (:bundler-version (opts project))]))



(defn- rake
  [project args]
    (ensure-gem project "rake")
    (jruby-exec project "-S" "rake" args))


(defn- bundle
  [project args]
  (ensure-bundler project)
  (if (or (empty? args) (= (first args) "install"))
    (jruby-exec project "-ropenssl" "-S" "bundle" "install" "--path" bundler-gem-path "--binstubs" bundler-binstubs-path)
    (jruby-exec project "-S" "bundle" args)))


(defn- gem
  [project args]
  (ensure-gem-dir project)
  (jruby-exec project "-S" "gem" args))


(defn jruby
  "Run a JRuby command"
  [project & args]
  ; TODO eval-in-project
  ; TODO add jruby to project dependencies
  (prn "foo")
  (ev/eval-in-project project
    (case (first args)
      "rake" (rake project (rest args))
      "bundle" (bundle project (rest args))
      "irb" (jruby-exec project project "-S" args)
      "gem" (gem project (rest args))
      "-S" (jruby-exec project args)
      "-v" (jruby-exec project args)
      "-e" (jruby-exec project args)
      (jruby-exec project args))))
