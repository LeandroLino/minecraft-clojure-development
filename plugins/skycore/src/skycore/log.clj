(ns skycore.log
  "Logging through the current plugin's logger. Works from any thread as long
  as the plugin binding is conveyed (the paperclj/skycore scheduler helpers do)."
  (:require [paperclj.core :as p])
  (:import (java.util.logging Level Logger)))

(set! *warn-on-reflection* true)

(defonce debug? (atom false))

(defn- logger ^Logger [] (.getLogger (p/plugin)))

(defn info [^String text] (.info (logger) text))

(defn warn [^String text] (.warning (logger) text))

(defn error
  ([^String text] (.severe (logger) text))
  ([^Throwable t ^String text] (.log (logger) Level/SEVERE text t)))

(defn debug
  "Logged only when `debug: true` in skycore's config.yml."
  [^String text]
  (when @debug? (.info (logger) (str "[debug] " text))))
