(ns paperclj.nrepl
  "Optional nREPL server for the shared runtime, bound to 127.0.0.1 only.
  Started by the paperclj plugin when its config.yml has nrepl.enabled: true."
  (:require [nrepl.server :as server])
  (:import (org.bukkit.plugin Plugin)))

(set! *warn-on-reflection* true)

(defonce ^:private server* (atom nil))

(defn stop! []
  (when-let [s @server*]
    (server/stop-server s)
    (reset! server* nil)))

(defn start!
  "Start nREPL on `port`, localhost only."
  [port ^Plugin plugin]
  (stop!)
  (reset! server* (server/start-server :bind "127.0.0.1" :port (long port)))
  (.info (.getLogger plugin) (str "nREPL listening on 127.0.0.1:" port)))
