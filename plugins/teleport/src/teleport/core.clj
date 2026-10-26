(ns teleport.core
  "Homes (per player), warps (server-wide) and tpa requests.

  Homes are cached per player by skycore.cache (loaded before the player
  joins); warps are loaded once at enable. Every change is written to SQLite on
  the db thread. Teleports use teleportAsync, which loads the target chunk
  without stalling the main thread."
  (:require [clojure.string :as str]
            [paperclj.core :as p]
            [skycore.cache :as cache]
            [skycore.cmd :as cmd]
            [skycore.config :as cfg]
            [skycore.db :as db]
            [skycore.events :as events]
            [skycore.log :as log]
            [skycore.msg :as msg]
            [skycore.services :as services]
            [teleport.names :as names]
            [teleport.requests :as requests])
  (:import (java.sql Connection)
           (java.util UUID)
           (org.bukkit Bukkit Location World)
           (org.bukkit.entity Player)
           (org.bukkit.event.player PlayerQuitEvent)))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil))     ; {:db :cache}
(defonce ^:private warps (atom nil))     ; name -> location map; nil until loaded
(defonce ^:private pending (atom requests/empty-requests))

;; ---------------------------------------------------------------- locations

(defn- loc->map [^Location l]
  {:world (.getName (.getWorld l)) :x (.getX l) :y (.getY l) :z (.getZ l)
   :yaw (double (.getYaw l)) :pitch (double (.getPitch l))})

(defn- row->loc [r]
  {:world (str (:world r)) :x (double (:x r)) :y (double (:y r)) :z (double (:z r))
   :yaw (double (:yaw r)) :pitch (double (:pitch r))})

(defn- map->loc ^Location [{:keys [world x y z yaw pitch]}]
  (when-let [^World w (Bukkit/getWorld ^String world)]
    (Location. w (double x) (double y) (double z) (float yaw) (float pitch))))

(defn- go! [^Player player loc-map]
  (if-let [loc (map->loc loc-map)]
    (do (msg/send! player "teleport.teleporting")
        (.teleportAsync player loc))
    (msg/send! player "teleport.world-missing" {:world (:world loc-map)})))

(defn- loc-params [name loc] [name (:world loc) (:x loc) (:y loc) (:z loc) (:yaw loc) (:pitch loc)])

;; ---------------------------------------------------------------- homes data

(defn- load-homes [^Connection conn ^UUID uuid]
  {:homes (into {} (map (fn [r] [(:name r) (row->loc r)]))
                (db/query-sql conn "SELECT name, world, x, y, z, yaw, pitch FROM homes WHERE uuid = ?" [(str uuid)]))})

(defn- homes-of [^UUID uuid]
  (:homes (cache/value (:cache @state) uuid)))

(defn- home-limit
  "Homes the player may have: the rank's limit, else the configured default."
  [^UUID uuid]
  (long (or (when-let [ranks (services/service :ranks)] ((:limit-of ranks) uuid :homes))
            (cfg/option "homes.default-limit"))))

(defn- name-ok? [^String n]
  (names/valid? n (cfg/option "name-max-length")))

(defn- bad-name [sender]
  (msg/send! sender "teleport.invalid-name" {:max (cfg/option "name-max-length")}))

;; ---------------------------------------------------------------- homes commands

(defn- sethome-command [^Player player args]
  (let [uuid (.getUniqueId player)
        n (names/normalize (or (first args) (cfg/option "homes.default-name")))
        homes (homes-of uuid)]
    (cond
      (> (count args) 1) false
      (not (name-ok? n)) (bad-name player)
      (nil? homes) (msg/send! player "core.error")
      :else
      (let [limit (home-limit uuid)
            decision (names/can-set-home homes n limit)]
        (if (= :limit decision)
          (msg/send! player "teleport.home-limit" {:count (count homes) :limit limit})
          (let [loc (loc->map (.getLocation player))]
            (cache/update! (:cache @state) uuid update :homes assoc n loc)
            (db/execute! (:db @state)
                         "INSERT OR REPLACE INTO homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                         (into [(str uuid)] (loc-params n loc)) nil)
            (msg/send! player (if (= :update decision) "teleport.home-updated" "teleport.home-set") {:name n})))))))

(defn- home-command [^Player player args]
  (let [homes (homes-of (.getUniqueId player))
        [kind v] (names/which-home homes (first args) (cfg/option "homes.default-name"))]
    (case kind
      :home (go! player (get homes v))
      :unknown (msg/send! player "teleport.home-unknown" {:name v})
      :choose (msg/send! player "teleport.home-choose" {:homes (str/join ", " v)})
      :none (msg/send! player "teleport.home-none"))))

(defn- delhome-command [^Player player args]
  (let [uuid (.getUniqueId player)
        n (names/normalize (first args))]
    (cond
      (not= 1 (count args)) false
      (not (contains? (homes-of uuid) n)) (msg/send! player "teleport.home-unknown" {:name n})
      :else (do (cache/update! (:cache @state) uuid update :homes dissoc n)
                (db/execute! (:db @state) "DELETE FROM homes WHERE uuid = ? AND name = ?" [(str uuid) n] nil)
                (msg/send! player "teleport.home-deleted" {:name n})))))

(defn- homes-command [^Player player]
  (let [uuid (.getUniqueId player)
        homes (homes-of uuid)]
    (if (empty? homes)
      (msg/send! player "teleport.home-none")
      (msg/send! player "teleport.homes-list" {:count (count homes) :limit (home-limit uuid)
                                               :homes (str/join ", " (sort (keys homes)))}))))

;; ---------------------------------------------------------------- warps

(defn- warps-ready? [sender]
  (or (some? @warps)
      (do (msg/send! sender "teleport.warps-loading") false)))

(defn- setwarp-command [^Player player args]
  (let [n (names/normalize (first args))]
    (cond
      (not= 1 (count args)) false
      (not (name-ok? n)) (bad-name player)
      (warps-ready? player)
      (let [loc (loc->map (.getLocation player))
            existed? (contains? @warps n)]
        (swap! warps assoc n loc)
        (db/execute! (:db @state)
                     "INSERT OR REPLACE INTO warps (name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)"
                     (loc-params n loc) nil)
        (msg/send! player (if existed? "teleport.warp-updated" "teleport.warp-set") {:name n})))))

(defn- delwarp-command [sender args]
  (let [n (names/normalize (first args))]
    (cond
      (not= 1 (count args)) false
      (warps-ready? sender)
      (if (contains? @warps n)
        (do (swap! warps dissoc n)
            (db/execute! (:db @state) "DELETE FROM warps WHERE name = ?" [n] nil)
            (msg/send! sender "teleport.warp-deleted" {:name n}))
        (msg/send! sender "teleport.warp-unknown" {:name n})))))

(defn- warp-command [^Player player args]
  (let [n (names/normalize (first args))]
    (cond
      (not= 1 (count args)) false
      (warps-ready? player)
      (if-let [loc (get @warps n)]
        (go! player loc)
        (msg/send! player "teleport.warp-unknown" {:name n})))))

(defn- warps-command [sender]
  (when (warps-ready? sender)
    (if (empty? @warps)
      (msg/send! sender "teleport.warps-none")
      (msg/send! sender "teleport.warps-list" {:warps (str/join ", " (sort (keys @warps)))}))))

;; ---------------------------------------------------------------- tpa

(defn- ttl-ms ^long [] (* 1000 (long (cfg/option "tpa.expire-seconds"))))

(defn- request! [^Player from args type]
  (let [^Player target (when (first args) (Bukkit/getPlayerExact ^String (first args)))]
    (cond
      (not= 1 (count args)) false
      (nil? target) (msg/send! from "core.player-not-found" {:player (first args)})
      (= (.getUniqueId target) (.getUniqueId from)) (msg/send! from "teleport.tpa-self")
      :else
      (do (swap! pending requests/add (.getUniqueId from) (.getUniqueId target) type (System/currentTimeMillis))
          (msg/send! from (if (= :tpa type) "teleport.tpa-sent" "teleport.tpa-sent-here") {:player (.getName target)})
          (msg/send! target (if (= :tpa type) "teleport.tpa-received" "teleport.tpa-received-here")
                     {:player (.getName from) :seconds (cfg/option "tpa.expire-seconds")})))))

(defn- answer! [^Player me args accept?]
  (let [named (first args)
        ^Player sender-player (when named (Bukkit/getPlayerExact ^String named))
        now (System/currentTimeMillis)
        r (when-not (and named (nil? sender-player))
            (requests/pick @pending (.getUniqueId me) (some-> sender-player .getUniqueId) now (ttl-ms)))]
    (cond
      (> (count args) 1) false
      (and named (nil? sender-player)) (msg/send! me "core.player-not-found" {:player named})
      (nil? r) (if named
                 (msg/send! me "teleport.tpa-none-from" {:player named})
                 (msg/send! me "teleport.tpa-none"))
      :else
      (let [^Player requester (Bukkit/getPlayer ^UUID (:from r))]
        (swap! pending requests/remove-request (:from r) (.getUniqueId me))
        (cond
          (nil? requester) (msg/send! me "teleport.tpa-offline")
          (not accept?) (do (msg/send! me "teleport.tpa-denied" {:player (.getName requester)})
                            (msg/send! requester "teleport.tpa-denied-notify" {:player (.getName me)}))
          :else (do (msg/send! me "teleport.tpa-accepted" {:player (.getName requester)})
                    (msg/send! requester "teleport.tpa-accepted-notify" {:player (.getName me)})
                    (if (= :tpa (:type r))
                      (.teleportAsync requester (.getLocation me))
                      (.teleportAsync me (.getLocation requester)))))))))

(defn- cancel! [^Player me]
  (let [[remaining removed] (requests/cancel-from @pending (.getUniqueId me))]
    (reset! pending remaining)
    (msg/send! me (if (pos? removed) "teleport.tpa-cancelled" "teleport.tpa-cancel-none"))))

;; ---------------------------------------------------------------- lifecycle

(defn- home-names [sender]
  (when (instance? Player sender)
    (keys (homes-of (.getUniqueId ^Player sender)))))

(p/defplugin
  (enable [_]
    (cfg/load!)
    (msg/load!)
    (let [d (db/open! (p/data-file "teleport.db"))]
      (db/execute! d "CREATE TABLE IF NOT EXISTS homes (uuid TEXT NOT NULL, name TEXT NOT NULL, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL, PRIMARY KEY (uuid, name))" [] nil)
      (db/execute! d "CREATE TABLE IF NOT EXISTS warps (name TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL)" [] nil)
      (reset! state {:db d})
      (swap! state assoc :cache (cache/create! ::teleport d load-homes))
      (reset! warps nil)
      (db/query! d "SELECT name, world, x, y, z, yaw, pitch FROM warps" []
                 (fn [rows] (reset! warps (into {} (map (fn [r] [(:name r) (row->loc r)])) rows)))))
    (reset! pending requests/empty-requests)

    (events/listen! ::quit PlayerQuitEvent {:priority :monitor}
                    (fn [^PlayerQuitEvent e]
                      (swap! pending requests/forget-player (.getUniqueId (.getPlayer e)))))

    (cmd/defcmd sethome {:permission "teleport.home" :player-only true
                         :description "Define uma home" :usage "/sethome [nome]"}
      [sender args] (sethome-command sender args))
    (cmd/defcmd home {:permission "teleport.home" :player-only true
                      :description "Vai para uma home" :usage "/home [nome]"
                      :complete (fn [sender args] (when (= 1 (count args)) (cmd/complete-prefix (home-names sender) (first args))))}
      [sender args] (if (<= (count args) 1) (home-command sender args) false))
    (cmd/defcmd delhome {:permission "teleport.home" :player-only true
                         :description "Apaga uma home" :usage "/delhome <nome>"
                         :complete (fn [sender args] (when (= 1 (count args)) (cmd/complete-prefix (home-names sender) (first args))))}
      [sender args] (delhome-command sender args))
    (cmd/defcmd homes {:permission "teleport.home" :player-only true
                       :description "Lista suas homes" :usage "/homes"}
      [sender _args] (homes-command sender))

    (cmd/defcmd setwarp {:permission "teleport.warp.set" :player-only true
                         :description "Cria uma warp aqui" :usage "/setwarp <nome>"}
      [sender args] (setwarp-command sender args))
    (cmd/defcmd delwarp {:permission "teleport.warp.set"
                         :description "Apaga uma warp" :usage "/delwarp <nome>"
                         :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-prefix (keys @warps) (first args))))}
      [sender args] (delwarp-command sender args))
    (cmd/defcmd warp {:permission "teleport.warp" :player-only true
                      :description "Vai para uma warp" :usage "/warp <nome>"
                      :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-prefix (keys @warps) (first args))))}
      [sender args] (warp-command sender args))
    (cmd/defcmd warps {:permission "teleport.warp" :description "Lista as warps" :usage "/warps"}
      [sender _args] (warps-command sender))

    (cmd/defcmd tpa {:permission "teleport.tpa" :player-only true
                     :description "Pede para ir até um jogador" :usage "/tpa <jogador>"
                     :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-players args)))}
      [sender args] (request! sender args :tpa))
    (cmd/defcmd tpahere {:permission "teleport.tpa" :player-only true
                         :description "Pede para um jogador vir até você" :usage "/tpahere <jogador>"
                         :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-players args)))}
      [sender args] (request! sender args :tpahere))
    (cmd/defcmd tpaccept {:permission "teleport.tpa" :player-only true :aliases ["tpyes"]
                          :description "Aceita um pedido de teleporte" :usage "/tpaccept [jogador]"
                          :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-players args)))}
      [sender args] (answer! sender args true))
    (cmd/defcmd tpdeny {:permission "teleport.tpa" :player-only true :aliases ["tpno"]
                        :description "Recusa um pedido de teleporte" :usage "/tpdeny [jogador]"
                        :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-players args)))}
      [sender args] (answer! sender args false))
    (cmd/defcmd tpcancel {:permission "teleport.tpa" :player-only true
                          :description "Cancela seus pedidos de teleporte" :usage "/tpcancel"}
      [sender _args] (cancel! sender))

    (log/info "teleport enabled"))

  (disable [_]
    (when-let [{:keys [db]} @state]
      (db/close! db))
    (reset! state nil)
    (reset! warps nil)
    (reset! pending requests/empty-requests)
    (log/info "teleport disabled")))
