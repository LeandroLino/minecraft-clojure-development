(ns join-greeter.core
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [paperclj.core :as p])
  (:import (java.io File)
           (org.bukkit.entity Player)
           (org.bukkit.event.player PlayerJoinEvent)))

(set! *warn-on-reflection* true)

(defn load-counts
  "Read the {uuid-string count} map from file; empty map if it does not exist."
  [^File file]
  (if (.exists file)
    (edn/read-string (slurp file))
    {}))

(defn save-counts! [^File file counts]
  (.mkdirs (.getParentFile file))
  (spit file (pr-str counts)))

(defn record-join!
  "Increment the join counter for uuid, persist it, and return the new count."
  [^File file uuid]
  (let [counts (update (load-counts file) uuid (fnil inc 0))]
    (save-counts! file counts)
    (get counts uuid)))

(defn render-message [template player-name n]
  (-> template
      (str/replace "{player}" player-name)
      (str/replace "{count}" (str n))))

(defn greet! [^Player player]
  (let [n (record-join! (p/data-file "joins.edn") (str (.getUniqueId player)))
        template (p/config "join-greeter.message" "Welcome, {player}!")]
    (p/send-text player (render-message template (.getName player) n))))

(p/defplugin
  (enable [_]
    (p/defevent on-join PlayerJoinEvent [^PlayerJoinEvent event]
      (greet! (.getPlayer event)))
    (p/log-info "join-greeter enabled"))
  (disable [_]
    (p/log-info "join-greeter disabled")))
