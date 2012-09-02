(ns leiningen.jruby
  (:use [clojure.java.io :only (file)])
  (:require [lancet.core :as lancet]
            [leiningen.classpath :as classpath]
            [clojure.string :as str])
  (:import [org.jruby Main]
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


; TODO implement
(defn- source-path
  [project]
  full-jruby-dir (file (:root project) "src"))


; here an ant task is set up to call jruby
(defn- task-props [project] {:classname "org.jruby.Main"})


(.addTaskDefinition lancet/ant-project "java" org.apache.tools.ant.taskdefs.Java)


(defn- create-jruby-task
  [project keys]
  ; TODO use source-path
  (let [full-jruby-dir (file (:root project) "src")
        url-classpath (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader)))
        classpath (str/join java.io.File/pathSeparatorChar (map #(.getPath %) url-classpath))
        task (doto (lancet/instantiate-task lancet/ant-project "java"
                                              (task-props project))
                  (.setClasspath (Path. lancet/ant-project classpath)))]

      (.setValue (.createArg task) (format "--%s" (:mode (opts project))))

      (doseq [k keys] (.setValue (.createArg task) k))

      (.setFailonerror task false)
      (.setFork task (not (= "irb" (second keys))))

      ; TODO pass in CWD
      ; i still don't get how it picks up the Gemfile and Rakefile with this set.. ?
      (if (.exists full-jruby-dir) (.setDir task full-jruby-dir))
      task))


(defn- set-env-var
  [task name value]
  (let [envvar (new Environment$Variable)]
    (.setKey envvar (.toUpperCase name)) 
    (.setValue envvar (str value))
    (.addEnv task envvar)))


(defn- set-gem-path
  [task gem-path]
  (set-env-var task "GEM_PATH" gem-path))


(defn- set-gem-home
  [task gem-home]
  (set-env-var task "GEM_HOME" gem-home))


(defn- prepend-path
  [task paths]
  (let [existing-path (System/getenv "PATH")
        updated-path (str/join ":" (flatten [paths existing-path]))]
    (set-env-var task "PATH" updated-path)))


(defn- process-args
  [project lumpy-args]
  (into-array String (flatten lumpy-args)))


(defn- jruby-exec
  [project & lumpy-args]
  (let [args (process-args project lumpy-args)
        task (create-jruby-task project args)
        root (:root project)]

    (prn "jruby " lumpy-args)

    ; TODO implement
    ; -I
    ;(.setValue (.createArg task) (format "-I%s" (.getAbsolutePath full-jruby-dir)))
    ;(.setValue (.createArg task) "-rubygems")

    (prepend-path task [(str root "/" rubygems-bin-path) (str root "/" bundler-binstubs-path)])

    (set-gem-home task (str root "/" rubygems-gem-path))
    (set-gem-path task (str root "/" rubygems-gem-path))

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
        version-string (when version (format "-v'%s'" version))]
  (jruby-exec project "-S" "maybe_install_gems" gem version-string)))


(defn- ensure-bundler
  [project]
  (ensure-gem-dir project)
  (ensure-gem project ["bundler" (:bundler-version (opts project))]))


(defn- rake
  [project args]
    (ensure-gem project "rake")
    (jruby-exec project "-S" "rake" args))


(defn- bundle
  [project args]
  (ensure-bundler project)
  (if (or (empty? args) (= (first args) "install"))
    (jruby-exec project "-S" "bundle" "install" "--path" bundler-gem-path "--binstubs" bundler-binstubs-path)
    (jruby-exec project "-S" "bundle" args)))


(defn- gem
  [project args]
  (ensure-gem-dir project)
  (jruby-exec project "-S" "gem" args))


(defn jruby
  "Run a JRuby command"
  [project & args]
  (case (first args)
    "rake" (rake project (rest args))
    "bundle" (bundle project (rest args))
    "irb" (jruby-exec project project "-S" args)
    "gem" (gem project (rest args))
    "-S" (jruby-exec project args)
    "-v" (jruby-exec project args)
    "-e" (jruby-exec project args)
    (jruby-exec project args)))
