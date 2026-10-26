(ns skycore.cache
  "Per-player data kept in memory while the player is online.

  The data is read from the module's database on the db thread BEFORE the
  player joins (AsyncPlayerPreLoginEvent runs off the main thread, so waiting
  for the read there never stalls the server), and dropped when the player
  quits. Handlers on the main thread therefore only ever read memory.

  If the read fails the login is refused instead of letting the player play
  with empty data (an empty balance, no homes) that would later overwrite the
  real rows."
  (:require [skycore.db :as db]
            [skycore.events :as events]
            [skycore.log :as log]
            [skycore.msg :as msg]
            [paperclj.core :as p])
  (:import (java.util UUID)
           (java.util.concurrent ConcurrentHashMap)
           (org.bukkit Bukkit)
           (org.bukkit.entity Player)
           (org.bukkit.event.player AsyncPlayerPreLoginEvent AsyncPlayerPreLoginEvent$Result PlayerQuitEvent)))

(set! *warn-on-reflection* true)

(defn create!
  "Start caching. `db` is the module's skycore.db handle; `load` is
  (fn [conn uuid]) run on the db thread and must return a non-nil value (the
  player's data, with defaults for a new player). `key` is a keyword unique to
  the module. Call it from `enable`."
  [key db load]
  (let [store (ConcurrentHashMap.)
        fetch (fn [^UUID uuid] (db/task! db (fn [conn] (load conn uuid)) nil))]
    (events/listen!
     (keyword (name key) "pre-login") AsyncPlayerPreLoginEvent {:priority :lowest}
     (fn [^AsyncPlayerPreLoginEvent event]
       (let [uuid (.getUniqueId event)
             data (try (.get ^java.util.concurrent.Future (fetch uuid)) (catch Throwable t (log/error t "loading player data failed") nil))]
         (if (nil? data)
           (.disallow event AsyncPlayerPreLoginEvent$Result/KICK_OTHER (msg/component "core.data-error"))
           (.put store uuid data)))))
    (events/listen!
     (keyword (name key) "quit") PlayerQuitEvent {:priority :monitor}
     (fn [^PlayerQuitEvent event]
       (.remove store (.getUniqueId (.getPlayer event)))))
    ;; players already online (a reload): load their data without blocking
    (doseq [^Player player (Bukkit/getOnlinePlayers)]
      (let [uuid (.getUniqueId player)]
        (db/task! db (fn [conn] (load conn uuid)) (fn [data] (when data (.put store uuid data))))))
    {:store store :db db}))

(defn value
  "The player's data, or nil when it is not loaded (not online)."
  [{:keys [^ConcurrentHashMap store]} ^UUID uuid]
  (.get store uuid))

(defn update!
  "Replace the player's data with (f data args...) and return the new value;
  nil if the player has no loaded data. Call it on the main thread."
  [{:keys [^ConcurrentHashMap store]} ^UUID uuid f & args]
  (.computeIfPresent store uuid (reify java.util.function.BiFunction
                                  (apply [_ _ old] (apply f old args)))))

(defn loaded-uuids [{:keys [^ConcurrentHashMap store]}]
  (vec (.keySet store)))
