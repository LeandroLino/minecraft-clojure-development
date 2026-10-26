(ns auth.core
  "Login and registration. Nobody plays before proving who they are: the server runs without
  Mojang's online mode, so without this anyone could join under somebody else's name.

  A player who is not logged in stands still where they joined (no moving, chat, commands except
  /login and /register, breaking, placing, using or taking things) and is kicked after
  login.timeout-seconds or login.max-attempts wrong passwords. The gate listens at LOWEST and does
  not skip events another plugin cancelled, so it acts before every other module and nothing it
  forbids can happen. Players who come back from the same address within login.remember-minutes of
  leaving are let in without a password (kept in memory only: a restart forgets it).

  Accounts live in SQLite (skycore.cache loads the player's row before the join), passwords as
  PBKDF2 hashes (auth.password) computed on the db thread. Other modules ask the :auth service."
  (:require [auth.logic :as logic]
            [auth.logmask :as logmask]
            [auth.password :as password]
            [clojure.string :as str]
            [paperclj.core :as p]
            [skycore.cache :as cache]
            [skycore.cmd :as cmd]
            [skycore.config :as cfg]
            [skycore.db :as db]
            [skycore.events :as events]
            [skycore.log :as log]
            [skycore.msg :as msg]
            [skycore.services :as services])
  (:import (java.sql Connection)
           (java.util UUID)
           (net.kyori.adventure.text Component)
           (org.bukkit Bukkit Location)
           (org.bukkit.entity Entity HumanEntity Player)
           (org.bukkit.event.block BlockBreakEvent BlockPlaceEvent)
           (org.bukkit.event.entity EntityDamageByEntityEvent EntityDamageEvent EntityPickupItemEvent)
           (org.bukkit.event.inventory InventoryClickEvent)
           (org.bukkit.event.player PlayerCommandPreprocessEvent PlayerCommandSendEvent PlayerDropItemEvent
                                    PlayerInteractEntityEvent PlayerInteractEvent PlayerJoinEvent
                                    PlayerMoveEvent PlayerQuitEvent)
           (org.bukkit.potion PotionEffect PotionEffectType)))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil))        ; {:db :cache}
;; uuid -> {:logged-in? :joined-ms :attempts :verifying? :walk-speed :last-reminder-ms}; one per player online
(defonce ^:private sessions (atom {}))
(defonce ^:private remembered (atom {}))    ; uuid -> {:ip :until-ms}

(def ^:private default-walk-speed 0.2)

(defn- now-ms ^long [] (System/currentTimeMillis))

(defn- ip-of ^String [^Player player]
  (some-> (.getAddress player) .getAddress .getHostAddress))

;; ---------------------------------------------------------------- accounts

(defn- load-account
  "Runs on the db thread: the player's account row as {:account {:name :salt :hash :iterations}},
  or {:account nil} for somebody who never registered."
  [^Connection conn ^UUID uuid]
  {:account (when-let [r (first (db/query-sql conn "SELECT name, salt, hash, iterations FROM accounts WHERE uuid = ?" [(str uuid)]))]
              {:name (str (:name r)) :salt (str (:salt r)) :hash (str (:hash r)) :iterations (long (:iterations r))})})

(defn- account-of [^UUID uuid]
  (:account (cache/value (:cache @state) uuid)))

(defn logged-in?
  "Whether the player is logged in. False for somebody who is not online."
  [^UUID uuid]
  (boolean (get-in @sessions [uuid :logged-in?])))

(defn- waiting?
  "Whether the player is online and not logged in yet: everything they try is refused."
  [^Entity entity]
  (and (instance? Player entity) (not (logged-in? (.getUniqueId entity)))))

;; ---------------------------------------------------------------- waiting to log in

(defn- freeze! [^Player player]
  (.setWalkSpeed player (float 0))
  (.addPotionEffect player (PotionEffect. PotionEffectType/BLINDNESS PotionEffect/INFINITE_DURATION 0 false false false)))

(defn- unfreeze! [^Player player walk-speed]
  (.setWalkSpeed player (float walk-speed))
  (.removePotionEffect player PotionEffectType/BLINDNESS))

(defn- update-session!
  "Change fields of the player's session, if they still have one (a callback may arrive after they left)."
  [uuid & kvs]
  (swap! sessions (fn [m] (if (contains? m uuid) (update m uuid #(apply assoc % kvs)) m))))

(defn- usage-of [account]
  (if account "/login <senha>" "/register <senha> <senha>"))

(defn- begin-wait!
  "The player has to log in: start their session, freeze them and tell them what to type."
  [^Player player]
  (let [uuid (.getUniqueId player)
        walk (let [w (.getWalkSpeed player)]
               ;; a walk speed left at 0 by a crash while waiting is not the player's own
               (if (< w 0.01) default-walk-speed w))
        timeout (long (cfg/option "login.timeout-seconds"))
        data (cache/value (:cache @state) uuid)]
    (swap! sessions assoc uuid {:logged-in? false :joined-ms (now-ms) :attempts 0 :verifying? false
                                :walk-speed walk :last-reminder-ms 0})
    (freeze! player)
    (cond
      (nil? data) (msg/send! player "auth.welcome-unknown")
      (:account data) (msg/send! player "auth.welcome-login" {:usage (usage-of true) :seconds timeout})
      :else (msg/send! player "auth.welcome-register"
                       {:usage (usage-of false) :seconds timeout
                        :min (long (cfg/option "password.min-length")) :max (long (cfg/option "password.max-length"))}))))

(defn- finish-login!
  "Let the player in."
  [^Player player]
  (let [uuid (.getUniqueId player)
        walk (get-in @sessions [uuid :walk-speed] default-walk-speed)]
    (update-session! uuid :logged-in? true :verifying? false)
    (unfreeze! player walk)
    (.sendActionBar player (Component/empty))))

(defn- on-join [^PlayerJoinEvent e]
  (let [player (.getPlayer e)
        uuid (.getUniqueId player)
        ip (ip-of player)
        data (cache/value (:cache @state) uuid)]
    (if (and (:account data) (logic/remembered? @remembered uuid ip (now-ms)))
      (do (swap! remembered dissoc uuid)
          (swap! sessions assoc uuid {:logged-in? true :joined-ms (now-ms) :attempts 0 :verifying? false
                                      :walk-speed default-walk-speed :last-reminder-ms 0})
          (msg/send! player "auth.remembered"))
      (begin-wait! player))))

(defn- on-quit [^PlayerQuitEvent e]
  (let [player (.getPlayer e)
        uuid (.getUniqueId player)
        session (get @sessions uuid)]
    (when session
      (if (:logged-in? session)
        (swap! remembered logic/remember uuid (ip-of player) (now-ms) (long (cfg/option "login.remember-minutes")))
        ;; do not save the frozen state in the player's data
        (unfreeze! player (:walk-speed session)))
      (swap! sessions dissoc uuid))
    (swap! remembered logic/prune (now-ms))))

(defn- tick!
  "Once a second: kick who ran out of time, remind the others every few seconds."
  []
  (let [now (now-ms)
        timeout (long (cfg/option "login.timeout-seconds"))
        every (* 1000 (long (cfg/option "login.reminder-seconds")))]
    (doseq [[uuid {:keys [logged-in? joined-ms last-reminder-ms]}] @sessions
            :when (not logged-in?)
            :let [^Player player (Bukkit/getPlayer ^UUID uuid)]
            :when player]
      (cond
        (logic/timed-out? joined-ms now timeout)
        (.kick player (msg/component "auth.timeout"))

        (>= (- now (long last-reminder-ms)) every)
        (do (swap! sessions assoc-in [uuid :last-reminder-ms] now)
            (msg/action-bar! player (if (account-of uuid) "auth.reminder-login" "auth.reminder-register")
                             {:usage (usage-of (account-of uuid))
                              :seconds (logic/seconds-left joined-ms now timeout)}))))))

;; ---------------------------------------------------------------- /register

(defn- register-in-db!
  "Runs on the db thread: hash the password and create the account, unless the name is taken
  (compared ignoring case, so Ana and ana cannot both exist). {:ok record} or {:error reason}."
  [^Connection conn ^UUID uuid ^String player-name ip ^String pw iterations]
  (try
    (let [lower (str/lower-case player-name)]
      (if (seq (db/query-sql conn "SELECT uuid FROM accounts WHERE name_lower = ? AND uuid <> ?" [lower (str uuid)]))
        {:error :name-taken}
        (let [record (password/create pw iterations)
              now (now-ms)]
          (db/exec-sql conn "INSERT INTO accounts (uuid, name, name_lower, salt, hash, iterations, created, last_ip, last_login) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                       [(str uuid) player-name lower (:salt record) (:hash record) (:iterations record) now ip now])
          {:ok (assoc record :name player-name)})))
    (catch Throwable t
      (log/error t "could not register an account")
      {:error :failed})))

(defn- on-registered [^Player player {:keys [ok error]}]
  (let [uuid (.getUniqueId player)]
    (update-session! uuid :verifying? false)
    (when (.isOnline player)
      (cond
        ok (do (cache/update! (:cache @state) uuid assoc :account ok)
               (finish-login! player)
               (msg/send! player "auth.registered-ok"))
        (= :name-taken error) (msg/send! player "auth.name-taken")
        :else (msg/send! player "auth.error")))))

(defn- register-command [^Player player args]
  (let [uuid (.getUniqueId player)
        session (get @sessions uuid)
        data (cache/value (:cache @state) uuid)
        min-length (long (cfg/option "password.min-length"))
        max-length (long (cfg/option "password.max-length"))]
    (cond
      (not= 2 (count args)) false
      (nil? session) (msg/send! player "auth.error")
      (:logged-in? session) (msg/send! player "auth.already-logged-in")
      (nil? data) (msg/send! player "auth.loading")
      (:account data) (msg/send! player "auth.already-registered")
      (:verifying? session) nil
      (not= (first args) (second args)) (msg/send! player "auth.password-mismatch")
      (= :too-short (logic/password-error (first args) min-length max-length))
      (msg/send! player "auth.password-too-short" {:min min-length})
      (= :too-long (logic/password-error (first args) min-length max-length))
      (msg/send! player "auth.password-too-long" {:max max-length})
      :else
      (let [pw (first args)
            player-name (.getName player)
            ip (ip-of player)
            iterations (long (cfg/option "password.hash-iterations"))]
        (update-session! uuid :verifying? true)
        (db/task! (:db @state)
                  (fn [conn] (register-in-db! conn uuid player-name ip pw iterations))
                  (fn [result] (on-registered player result)))))))

;; ---------------------------------------------------------------- /login

(defn- on-login-result [^Player player ok?]
  (let [uuid (.getUniqueId player)]
    (update-session! uuid :verifying? false)
    (when (.isOnline player)
      (if ok?
        (do (finish-login! player)
            (db/execute! (:db @state) "UPDATE accounts SET last_ip = ?, last_login = ? WHERE uuid = ?"
                         [(ip-of player) (now-ms) (str uuid)] nil)
            (msg/send! player "auth.login-ok"))
        (let [{:keys [attempts left kick?]} (logic/wrong-password (get-in @sessions [uuid :attempts] 0)
                                                                  (long (cfg/option "login.max-attempts")))]
          (update-session! uuid :attempts attempts)
          (if kick?
            (.kick player (msg/component "auth.too-many-attempts"))
            (msg/send! player "auth.wrong-password" {:left left})))))))

(defn- login-command [^Player player args]
  (let [uuid (.getUniqueId player)
        session (get @sessions uuid)
        data (cache/value (:cache @state) uuid)]
    (cond
      (not= 1 (count args)) false
      (nil? session) (msg/send! player "auth.error")
      (:logged-in? session) (msg/send! player "auth.already-logged-in")
      (nil? data) (msg/send! player "auth.loading")
      (nil? (:account data)) (msg/send! player "auth.not-registered" {:usage (usage-of false)})
      (:verifying? session) nil
      :else
      (let [pw (first args)
            account (:account data)]
        (update-session! uuid :verifying? true)
        ;; the hash is computed on the db thread, not on the main one
        (db/task! (:db @state)
                  (fn [_conn] (password/verify pw account))
                  (fn [ok?] (on-login-result player ok?)))))))

;; ---------------------------------------------------------------- for other modules

(defn- set-password!
  "Give the account of the player called `player-name` a new password, for /sky senha. `callback` is
  called on the main thread with :ok, :no-account, :too-short or :too-long."
  [^String player-name ^String new-password callback]
  (let [min-length (long (cfg/option "password.min-length"))
        max-length (long (cfg/option "password.max-length"))
        iterations (long (cfg/option "password.hash-iterations"))]
    (if-let [problem (logic/password-error new-password min-length max-length)]
      (callback problem)
      (db/task! (:db @state)
                (fn [^Connection conn]
                  (if-let [row (first (db/query-sql conn "SELECT uuid FROM accounts WHERE name_lower = ?" [(str/lower-case player-name)]))]
                    (let [record (password/create new-password iterations)]
                      (db/exec-sql conn "UPDATE accounts SET salt = ?, hash = ?, iterations = ? WHERE uuid = ?"
                                   [(:salt record) (:hash record) (:iterations record) (:uuid row)])
                      {:uuid (str (:uuid row)) :record record})
                    {:error :no-account}))
                (fn [{:keys [uuid record error]}]
                  (when (and uuid record)
                    ;; a player online with an account loaded gets the new password in memory too
                    (let [u (UUID/fromString ^String uuid)]
                      (cache/update! (:cache @state) u
                                     (fn [data] (if (:account data) (update data :account merge record) data)))))
                  (callback (or error :ok)))))))

;; ---------------------------------------------------------------- the gate

(defn- deny-action!
  "Tell an unlogged player they have to log in first."
  [^Player player]
  (msg/action-bar! player "auth.blocked" {:usage (usage-of (account-of (.getUniqueId player)))}))

(defn- on-move [^PlayerMoveEvent e]
  (let [player (.getPlayer e)]
    (when (waiting? player)
      (let [^Location from (.getFrom e)
            ^Location to (.getTo e)]
        ;; looking around is fine; going anywhere is not
        (when (or (not= (.getX from) (.getX to)) (not= (.getY from) (.getY to)) (not= (.getZ from) (.getZ to)))
          (let [back (.clone from)]
            (.setYaw back (.getYaw to))
            (.setPitch back (.getPitch to))
            (.setTo e back)))))))

(defn- on-chat
  "Runs on a worker thread: touches only memory, nothing of Bukkit but the id."
  [^io.papermc.paper.event.player.AsyncChatEvent e]
  (when (waiting? (.getPlayer e))
    (.setCancelled e true)))

(defn- on-command [^PlayerCommandPreprocessEvent e]
  (let [player (.getPlayer e)]
    (when (and (waiting? player) (not (logic/allowed-command? (.getMessage e))))
      (.setCancelled e true)
      (deny-action! player))))

(defn- on-command-list
  "Show an unlogged player only the commands they can use."
  [^PlayerCommandSendEvent e]
  (when (waiting? (.getPlayer e))
    (.retainAll (.getCommands e) #{"login" "register"})))

(defn- cancel-if-waiting! [^org.bukkit.event.Cancellable e ^Entity who]
  (when (waiting? who)
    (.setCancelled e true)
    (when (instance? Player who) (deny-action! who))))

(defn- listen-gate! []
  (let [first-opts {:priority :lowest :ignore-cancelled false}]
    (events/listen! ::move PlayerMoveEvent first-opts on-move)
    (events/listen! ::chat io.papermc.paper.event.player.AsyncChatEvent first-opts on-chat)
    (events/listen! ::command PlayerCommandPreprocessEvent first-opts on-command)
    (events/listen! ::command-list PlayerCommandSendEvent first-opts on-command-list)
    (events/listen! ::interact PlayerInteractEvent first-opts
                    (fn [^PlayerInteractEvent e] (cancel-if-waiting! e (.getPlayer e))))
    (events/listen! ::interact-entity PlayerInteractEntityEvent first-opts
                    (fn [^PlayerInteractEntityEvent e] (cancel-if-waiting! e (.getPlayer e))))
    (events/listen! ::break BlockBreakEvent first-opts
                    (fn [^BlockBreakEvent e] (cancel-if-waiting! e (.getPlayer e))))
    (events/listen! ::place BlockPlaceEvent first-opts
                    (fn [^BlockPlaceEvent e] (cancel-if-waiting! e (.getPlayer e))))
    (events/listen! ::drop PlayerDropItemEvent first-opts
                    (fn [^PlayerDropItemEvent e] (cancel-if-waiting! e (.getPlayer e))))
    (events/listen! ::pickup EntityPickupItemEvent first-opts
                    (fn [^EntityPickupItemEvent e] (when (waiting? (.getEntity e)) (.setCancelled e true))))
    (events/listen! ::inventory InventoryClickEvent first-opts
                    (fn [^InventoryClickEvent e]
                      (let [^HumanEntity who (.getWhoClicked e)]
                        (when (waiting? who) (.setCancelled e true)))))
    ;; nothing hurts them, and they hurt nobody
    (events/listen! ::damage EntityDamageEvent first-opts
                    (fn [^EntityDamageEvent e] (when (waiting? (.getEntity e)) (.setCancelled e true))))
    (events/listen! ::damage-by EntityDamageByEntityEvent first-opts
                    (fn [^EntityDamageByEntityEvent e] (when (waiting? (.getDamager e)) (.setCancelled e true))))))

;; ---------------------------------------------------------------- lifecycle

(p/defplugin
  (enable [_]
    (cfg/load!)
    (msg/load!)
    (let [d (db/open! (p/data-file "auth.db"))]
      (db/execute! d "CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT NOT NULL UNIQUE, salt TEXT NOT NULL, hash TEXT NOT NULL, iterations INTEGER NOT NULL, created INTEGER NOT NULL, last_ip TEXT, last_login INTEGER)" [] nil)
      (reset! state {:db d})
      (swap! state assoc :cache (cache/create! ::auth d load-account)))
    (reset! sessions {})
    (reset! remembered {})

    (listen-gate!)
    (events/listen! ::join PlayerJoinEvent {:priority :lowest} on-join)
    (events/listen! ::quit PlayerQuitEvent {:priority :normal} on-quit)
    (p/run-repeating! ::tick 20 20 tick!)

    ;; players online while the plugin starts (a reload) have to log in too
    (doseq [^Player player (Bukkit/getOnlinePlayers)]
      (begin-wait! player))

    (cmd/defcmd register {:description "Cria a sua conta" :usage "/register <senha> <senha>" :player-only true}
      [sender args] (register-command sender args))
    (cmd/defcmd login {:description "Entra na sua conta" :usage "/login <senha>" :player-only true}
      [sender args] (login-command sender args))

    (services/provide! :auth {:logged-in? logged-in?
                              :set-password! set-password!})
    (logmask/install!)
    (log/info "auth enabled"))

  (disable [_]
    (services/release-all!)
    (logmask/uninstall!)
    ;; give back what waiting took from the players still online
    (doseq [[uuid {:keys [logged-in? walk-speed]}] @sessions
            :when (not logged-in?)
            :let [^Player player (Bukkit/getPlayer ^UUID uuid)]
            :when player]
      (unfreeze! player walk-speed))
    (reset! sessions {})
    (reset! remembered {})
    (when-let [d (:db @state)] (db/close! d))
    (reset! state nil)
    (log/info "auth disabled")))
