(ns admin.players
  "Admin tools that act on players: fly, vanish, mute and heal.

  Mutes are kept in memory (the chat handler runs on Paper's async chat thread
  and must not wait for the database) and written to SQLite, so a muted player
  cannot escape by reconnecting or when the server restarts. Vanish is held in
  memory only: it ends when the player leaves."
  (:require [admin.mutes :as mutes]
            [paperclj.core :as p]
            [skycore.db :as db]
            [skycore.events :as events]
            [skycore.log :as log]
            [skycore.msg :as msg])
  (:import (io.papermc.paper.event.player AsyncChatEvent)
           (java.util UUID)
           (org.bukkit Bukkit GameMode OfflinePlayer)
           (org.bukkit.attribute Attribute)
           (org.bukkit.entity Player)
           (org.bukkit.event.player PlayerJoinEvent PlayerQuitEvent)
           (org.bukkit.plugin Plugin)
           (org.bukkit.potion PotionEffect)))

(set! *warn-on-reflection* true)

(defonce ^:private db-handle (atom nil))
(defonce ^:private mute-table (atom {}))   ; uuid string -> {:name :until}
(defonce ^:private vanished (atom #{}))    ; UUIDs of vanished players

;; ---------------------------------------------------------------- lookups

(defn- online ^Player [^String name] (Bukkit/getPlayerExact name))

(defn- resolve-player
  "{:uuid :name} of a player the server knows by name (online, or cached from an
  earlier visit), else nil. Never asks Mojang, so it never blocks."
  [^String name]
  (if-let [^Player p (online name)]
    {:uuid (str (.getUniqueId p)) :name (.getName p)}
    (when-let [^OfflinePlayer o (Bukkit/getOfflinePlayerIfCached name)]
      {:uuid (str (.getUniqueId o)) :name (or (.getName o) name)})))

(defn- not-found [sender name]
  (msg/send! sender "core.player-not-found" {:player name}))

(defn- target-of
  "The player a command acts on: the named online player, or the sender when no
  name is given. Replies and returns nil when there is no such player."
  [sender args]
  (if-let [name (first args)]
    (or (online name) (do (not-found sender name) nil))
    (if (instance? Player sender)
      sender
      (do (msg/send! sender "core.player-only") nil))))

;; ---------------------------------------------------------------- fly

(defn fly!
  "/sky fly [player]: toggle flight."
  [sender args]
  (when-let [^Player target (target-of sender args)]
    (let [self? (identical? target sender)
          gm (.getGameMode target)]
      (if (or (= gm GameMode/CREATIVE) (= gm GameMode/SPECTATOR))
        (msg/send! sender "admin.fly-gamemode" {:player (.getName target)})
        (let [on? (not (.getAllowFlight target))]
          (.setAllowFlight target on?)
          (when on? (.setFlying target true))
          (when-not on? (.setFlying target false))
          (if self?
            (msg/send! sender (if on? "admin.fly-on-self" "admin.fly-off-self"))
            (do (msg/send! sender (if on? "admin.fly-on-other" "admin.fly-off-other") {:player (.getName target)})
                (msg/send! target (if on? "admin.fly-notify-on" "admin.fly-notify-off")))))))))

;; ---------------------------------------------------------------- heal

;; Minecraft's own maximums, not game rules: a full food bar and the default health.
(def ^:private max-food-level 20)
(def ^:private default-max-health 20.0)

(defn heal!
  "/sky heal [player]: full health and food, no fire, no potion effects, no fall damage pending."
  [sender args]
  (when-let [^Player target (target-of sender args)]
    (if (.isDead target)
      (msg/send! sender "admin.heal-dead" {:player (.getName target)})
      (let [max-health (or (some-> (.getAttribute target Attribute/MAX_HEALTH) .getValue) default-max-health)]
        (.setHealth target (double max-health))
        (.setFoodLevel target (int max-food-level))
        (.setSaturation target (float max-food-level))
        (.setFireTicks target 0)
        (.setFallDistance target (float 0))
        (doseq [^PotionEffect effect (vec (.getActivePotionEffects target))]
          (.removePotionEffect target (.getType effect)))
        (if (identical? target sender)
          (msg/send! sender "admin.heal-self")
          (do (msg/send! sender "admin.heal-other" {:player (.getName target)})
              (msg/send! target "admin.heal-notify")))))))

;; ---------------------------------------------------------------- vanish

(defn- set-vanished!
  "Hide (or show) `target` for every viewer who may not see vanished players."
  [^Player target hide?]
  (let [^Plugin plugin (p/plugin)]
    (doseq [^Player viewer (Bukkit/getOnlinePlayers)
            :when (and (not (identical? viewer target)) (not (.hasPermission viewer "admin.vanish.see")))]
      (if hide? (.hidePlayer viewer plugin target) (.showPlayer viewer plugin target)))
    ;; mobs do not count a vanished player for spawning, nobody bumps into them, and they do not hold up the night
    (.setAffectsSpawning target (not hide?))
    (.setCollidable target (not hide?))
    (.setSleepingIgnored target hide?)
    (if hide?
      (swap! vanished conj (.getUniqueId target))
      (swap! vanished disj (.getUniqueId target)))))

(defn vanish!
  "/sky vanish [player]: toggle invisibility."
  [sender args]
  (when-let [^Player target (target-of sender args)]
    (let [self? (identical? target sender)
          hide? (not (contains? @vanished (.getUniqueId target)))]
      (set-vanished! target hide?)
      (if self?
        (msg/send! sender (if hide? "admin.vanish-on-self" "admin.vanish-off-self"))
        (do (msg/send! sender (if hide? "admin.vanish-on-other" "admin.vanish-off-other") {:player (.getName target)})
            (msg/send! target (if hide? "admin.vanish-on-self" "admin.vanish-off-self")))))))

(defn vanished?
  "Whether this player is vanished right now. Published as the :admin service so
  other modules (the tab and scoreboard player count) can leave them out."
  [^UUID uuid]
  (contains? @vanished uuid))

(defn- hide-vanished-from! [^Player joiner]
  (when-not (.hasPermission joiner "admin.vanish.see")
    (let [^Plugin plugin (p/plugin)]
      (doseq [uuid @vanished
              :let [^Player target (Bukkit/getPlayer ^UUID uuid)]
              :when target]
        (.hidePlayer joiner plugin target)))))

(defn- on-join [^PlayerJoinEvent e]
  (hide-vanished-from! (.getPlayer e)))

(defn- on-quit [^PlayerQuitEvent e]
  (let [uuid (.getUniqueId (.getPlayer e))]
    (when (contains? @vanished uuid)
      (.quitMessage e nil)                ; leaving while vanished is silent
      (swap! vanished disj uuid))))

;; ---------------------------------------------------------------- mute

(defn- save-mute! [uuid {:keys [name] :as entry}]
  (swap! mute-table assoc uuid entry)
  (db/execute! @db-handle "INSERT OR REPLACE INTO mutes (uuid, name, until) VALUES (?, ?, ?)"
               [uuid name (mutes/->db-until entry)] nil))

(defn- delete-mute! [uuid]
  (swap! mute-table dissoc uuid)
  (db/execute! @db-handle "DELETE FROM mutes WHERE uuid = ?" [uuid] nil))

(defn mute!
  "/sky mute <player> [minutes]: silence a player; no minutes means until unmuted."
  [sender args]
  (let [[name text] args
        duration (when name (mutes/parse-duration text))
        target (when name (resolve-player name))]
    (cond
      (or (nil? name) (> (count args) 2) (nil? duration)) false
      (nil? target) (not-found sender name)
      :else
      (let [now (System/currentTimeMillis)
            entry {:name (:name target) :until (mutes/until-millis now duration)}
            minutes (second duration)
            ^Player live (online name)]
        (save-mute! (:uuid target) entry)
        (if (= :minutes (first duration))
          (do (msg/send! sender "admin.mute-ok-timed" {:player (:name target) :minutes minutes})
              (when live (msg/send! live "admin.mute-notify-timed" {:minutes minutes})))
          (do (msg/send! sender "admin.mute-ok-permanent" {:player (:name target)})
              (when live (msg/send! live "admin.mute-notify-permanent"))))))))

(defn unmute!
  "/sky unmute <player>."
  [sender args]
  (if (not= 1 (count args))
    false
    (let [name (first args)
          target (resolve-player name)
          ^Player live (online name)]
      (cond
        (nil? target) (not-found sender name)
        (not (contains? @mute-table (:uuid target))) (msg/send! sender "admin.unmute-not-muted" {:player (:name target)})
        :else (do (delete-mute! (:uuid target))
                  (msg/send! sender "admin.unmute-ok" {:player (:name target)})
                  (when live (msg/send! live "admin.unmute-notify")))))))

(defn- on-chat
  "Runs on Paper's async chat thread: only memory, no database wait."
  [^AsyncChatEvent e]
  (let [^Player player (.getPlayer e)
        uuid (str (.getUniqueId player))]
    (when-let [entry (get @mute-table uuid)]
      (let [now (System/currentTimeMillis)]
        (if (mutes/active? entry now)
          (do (.setCancelled e true)
              (if-let [minutes (mutes/remaining-minutes entry now)]
                (msg/send! player "admin.muted-timed" {:minutes minutes})
                (msg/send! player "admin.muted-permanent")))
          (delete-mute! uuid))))))      ; the time is up: forget it

;; ---------------------------------------------------------------- lifecycle

(defn install!
  "Open the database, load the mutes and register the listeners. Call from enable."
  []
  (let [d (db/open! (p/data-file "admin.db"))]
    (reset! db-handle d)
    (db/execute! d "CREATE TABLE IF NOT EXISTS mutes (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, until INTEGER NOT NULL)" [] nil)
    (db/query! d "SELECT uuid, name, until FROM mutes" []
               (fn [rows]
                 (reset! mute-table (into {} (map (fn [r] [(:uuid r) {:name (:name r) :until (mutes/<-db-until (:until r))}])) rows))
                 (log/info (str "mutes loaded: " (count rows))))))
  (reset! vanished #{})
  ;; lowest priority: the mute cancels the message before the chat module formats it
  (events/listen! ::chat AsyncChatEvent {:priority :lowest :ignore-cancelled true} on-chat)
  (events/listen! ::join PlayerJoinEvent {:priority :high} on-join)
  ;; highest priority: overrides the leave message the chat module sets
  (events/listen! ::quit PlayerQuitEvent {:priority :highest} on-quit))

(defn uninstall!
  "Show every vanished player again and close the database. Call from disable."
  []
  (doseq [uuid @vanished
          :let [^Player target (Bukkit/getPlayer ^UUID uuid)]
          :when target]
    (set-vanished! target false))
  (when-let [d @db-handle] (db/close! d))
  (reset! db-handle nil)
  (reset! mute-table {}))
