(ns leiningen.jruby
  (:use [clojure.java.io :only [file as-file]]
        [clojure.java.shell :only [sh]])
  (:require [lancet.core :as lancet]
            [leiningen.classpath :as cp]
            [leiningen.core.eval :as eip]
            [clojure.string :as str])
  (:import [org.jruby Main RubyInstanceConfig]
           [org.jruby.util JRubyFile]))
           ;[jnr.posix POSIX POSIXFactory]))


(def default-options
  {:mode "1.9"
   :bundler-version "1.2.0"})


(defn- opts [project]
    (merge default-options (:jruby-options project)))


; paths
(def gem-dir ".lein-gems/gems")
(def bundle-dir ".lein-gems/bundled")


(defn- bundler-18-gem-path 
  [project]
  (str (:root project) "/" gem-dir "/jruby/1.8"))


(defn- bundler-19-gem-path 
  [project]
  (str (:root project) "/" gem-dir "/jruby/1.9"))


(defn- gem-path
  [project]
  (str (:root project) "/" gem-dir))


(defn- bundler-gem-path 
  [project]
  (if (= (:mode (opts project)) "1.8") 
    (bundler-18-gem-path project)
    (bundler-19-gem-path project)))


(defn- bundler-version
  [project]
  (:bundler-version (opts project)))


(def rubygems-gem-path (str gem-dir "/gems"))


(defn- gem-install-dir-arg 
  [project]
  (format "-i%s" (str (:root project) "/" rubygems-gem-path)))


(defn- gem-bundler-install-dir-arg 
  [project]
  (format "-i%s" (bundler-gem-path project)))


(defn- process-args
  [project args]
  (let [mode-arg (str "--" (:mode (opts project)))]
    (into-array String (flatten [ mode-arg args ] ))))


(defn- jruby-config
  [project]
  (let [jvm-env (System/getenv)
        gem-path (gem-path project)
        path (str gem-path "/bin" ":" (get jvm-env "PATH"))
        env (merge {} jvm-env {"GEM_PATH" gem-path "GEM_HOME" gem-path "PATH" path})
        ]
    (prn "new path" env)
  (doto (RubyInstanceConfig.)
    (.setEnvironment env))))


; core exec 
(defn- jruby-exec
  [project & args]
  (let [jruby (org.jruby.Main. (jruby-config project))
        final-args (process-args project args)]

    (prn "jruby" (seq final-args))
    (.run jruby final-args)))


(defn- jruby-bundle-exec
  [project & args]
  )
  ;(let [task (create-jruby-task project keys)
        ;bundler-path (bundler-gem-path project)] 

    ;(println (str "bundle exec" keys))

    ;; this may not be a good idea, but can't find another way to get the rubygems bin picked up
    ;; another option might be to put it on the classpath. kind of a pain to do that and I'm lazy
    ;; right now :P
    ;(apply set-path [task project bundler-path]) 

    ;(apply set-gem-home [task bundler-path])
    ;(apply set-gem-path [task bundler-path]) 

    ;(.execute task)))


(defn- any-starts-with?
  [prefix strs]
  (some (fn [str] (.startsWith str prefix)) strs))


(defn- ensure-gem-dir 
  [project]
  (.mkdir (file (:root project) gem-dir)))


(defn- ensure-gems
  [project & gems]
  (ensure-gem-dir project)
  (jruby-exec project "-S" "maybe_install_gems" gems (gem-install-dir-arg project)))


(defn- ensure-bundler
  [project]
  (ensure-gem-dir project)

  ;yea, not really bundle execing, but we need that stuff on the gem path
  (jruby-exec project
    "-S" "maybe_install_gems"
    "bundler"
      (format "-v%s" (bundler-version project))))
      ;(gem-bundler-install-dir-arg project)]))


(defn- ensure-gem
  [project gem]
  (ensure-gems project gem))


(defn- rake
  [project args]
    (ensure-gem project "rake")
    (jruby-exec project "-S" "rake" args))


(defn- bundle
  [project args]
  (apply ensure-bundler [project])
  (if (or (empty? args) (= (first args) "install"))
    (apply jruby-bundle-exec project (concat ["-S" "bundle"] args ["--path" gem-dir]))
    (if (= "exec" (first args))
      (jruby-bundle-exec project (concat ["-S"] (rest args)))
      (jruby-bundle-exec project (concat ["-S" "bundle"] args)))))


(defn- gem
  [project args]
  (ensure-gem-dir project)
  (if (any-starts-with? (first args) ["install" "uninstall" "update"])
    (jruby-exec project "-S" "gem" args (gem-install-dir-arg project))
    (jruby-exec project "-S" "gem" args)))


(defn jruby
  "Run a JRuby command"
  [project & args]
    (println "jruby..." args)
    (println "here")
    ; (doto POSIXFactory/getPOSIX (setenv "FOOBAT" "HI"))
    ;(prn project)
    ;(eip/eval-in-project project
      (case (first args)
        "rake"   (rake project (rest args))
        "bundle" (bundle project (rest args))
        "irb"    (jruby-exec project "-S" args)
        "gem"    (gem project (rest args))
        (jruby-exec project args)))
