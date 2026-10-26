(ns ranks.core
  "Rank ladder: every player has one rank, bought in sequence with currency.
  A rank grants permissions (through a PermissionAttachment, so nothing is
  written to a permissions file) and numeric limits other modules read
  through the :ranks service."
  (:require [clojure.string :as str]
            [paperclj.core :as p]
            [ranks.ladder :as ladder]
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
           (org.bukkit Bukkit)
           (org.bukkit.command CommandSender)
           (org.bukkit.entity Player)
           (org.bukkit.event.player PlayerJoinEvent PlayerQuitEvent)
           (org.bukkit.permissions PermissionAttachment)
           (org.bukkit.plugin Plugin)))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil))        ; {:db :cache :ladder}
(defonce ^:private attachments (atom {}))   ; uuid -> PermissionAttachment

;; ---------------------------------------------------------------- data

(defn- load-rank
  "Runs on the db thread: the stored rank id, or the first rank (stored) for a new player."
  [^Connection conn ^UUID uuid]
  (if-let [row (first (db/query-sql conn "SELECT rank FROM player_ranks WHERE uuid = ?" [(str uuid)]))]
    {:rank (str (:rank row))}
    (let [first-id (:id (first (:ladder @state)))]
      (db/exec-sql conn "INSERT INTO player_ranks (uuid, rank) VALUES (?, ?)" [(str uuid) first-id])
      {:rank first-id})))

(defn rank-of
  "The player's rank map ({:id :name :prefix :price ...}). A rank id that is no
  longer in the config counts as the first rank. nil when the data is not loaded."
  [^UUID uuid]
  (when-let [{:keys [rank]} (cache/value (:cache @state) uuid)]
    (let [l (:ladder @state)]
      (or (ladder/find-rank l rank) (first l)))))

(defn prefix-of
  "MiniMessage prefix of the player's rank, \"\" when unknown."
  [^UUID uuid]
  (or (:prefix (rank-of uuid)) ""))

(defn limit-of
  "A numeric limit (e.g. :homes) granted by the player's rank, or nil."
  [^UUID uuid limit-name]
  (when-let [r (rank-of uuid)]
    (ladder/limit (:ladder @state) (:id r) limit-name)))

;; ---------------------------------------------------------------- permissions

(defn- clear-attachment! [^UUID uuid]
  (when-let [^PermissionAttachment old (get @attachments uuid)]
    (.remove old)
    (swap! attachments dissoc uuid)))

(defn- apply-rank!
  "(Re)attach the permissions of the player's current rank."
  [^Player player]
  (let [uuid (.getUniqueId player)]
    (clear-attachment! uuid)
    (when-let [r (rank-of uuid)]
      (let [attachment (.addAttachment player ^Plugin (p/plugin))]
        (doseq [^String permission (:permissions (ladder/effective (:ladder @state) (:id r)))]
          (.setPermission attachment permission true))
        (swap! attachments assoc uuid attachment)))
    (.updateCommands player)))

(defn set-rank!
  "Give the online player rank `rank-id` (must exist) and persist it."
  [^Player player ^String rank-id]
  (let [uuid (.getUniqueId player)]
    (cache/update! (:cache @state) uuid assoc :rank rank-id)
    (db/execute! (:db @state)
                 "INSERT OR REPLACE INTO player_ranks (uuid, rank) VALUES (?, ?)"
                 [(str uuid) rank-id] nil)
    (apply-rank! player)))

;; ---------------------------------------------------------------- commands

(defn- money [n]
  (if-let [eco (services/service :economy)] ((:format eco) n) (str n)))

(defn- info-command [^Player sender]
  (when-let [r (rank-of (.getUniqueId sender))]
    (msg/send! sender "ranks.info" {:rank (:name r)})
    (if-let [n (ladder/next-rank (:ladder @state) (:id r))]
      (msg/send! sender "ranks.info-next" {:next (:name n) :price (money (:price n))})
      (msg/send! sender "ranks.info-max"))))

(defn- list-command [^CommandSender sender]
  (let [current (when (instance? Player sender) (:id (rank-of (.getUniqueId ^Player sender))))]
    (msg/send! sender "ranks.list-header")
    (doseq [r (:ladder @state)]
      (msg/send! sender (if (= current (:id r)) "ranks.list-line-current" "ranks.list-line")
                 {:rank (:name r) :price (money (:price r))}))))

(defn- up-command [^Player sender]
  (let [uuid (.getUniqueId sender)
        eco (services/service :economy)
        current (rank-of uuid)
        n (when current (ladder/next-rank (:ladder @state) (:id current)))]
    (cond
      (nil? current) (msg/send! sender "ranks.up-error")
      (nil? n) (msg/send! sender "ranks.up-max")
      (nil? eco) (msg/send! sender "ranks.up-error")
      :else
      (let [price (:price n)
            result (if (zero? price) {:ok ((:balance eco) uuid)} ((:withdraw! eco) uuid price))]
        (case (:error result)
          nil (do (set-rank! sender (:id n))
                  (msg/send! sender "ranks.up-ok" {:rank (:name n) :price (money price)}))
          :insufficient (msg/send! sender "ranks.up-insufficient"
                                   {:price (money price) :next (:name n) :balance (money ((:balance eco) uuid))})
          (msg/send! sender "ranks.up-error"))))))

(defn- set-command [^CommandSender sender args]
  (let [[player-name rank-id] (rest args)
        ^Player target (when player-name (Bukkit/getPlayerExact player-name))
        r (when rank-id (ladder/find-rank (:ladder @state) rank-id))]
    (cond
      (not= 3 (count args)) false
      (nil? target) (msg/send! sender "core.player-not-found" {:player player-name})
      (nil? r) (msg/send! sender "ranks.unknown-rank"
                          {:rank rank-id :ranks (str/join ", " (map :id (:ladder @state)))})
      :else (do (set-rank! target (:id r))
                (msg/send! sender "ranks.set-ok" {:player (.getName target) :rank (:name r)})
                (msg/send! target "ranks.set-notified" {:rank (:name r)})))))

;; ---------------------------------------------------------------- lifecycle

(p/defplugin
  (enable [_]
    (cfg/load!)
    (msg/load!)
    (let [l (ladder/parse (cfg/option "ranks"))
          d (db/open! (p/data-file "ranks.db"))]
      (db/execute! d "CREATE TABLE IF NOT EXISTS player_ranks (uuid TEXT PRIMARY KEY, rank TEXT NOT NULL)" [] nil)
      (reset! state {:db d :ladder l})
      (swap! state assoc :cache (cache/create! ::ranks d load-rank)))

    ;; permissions follow the player: attached on join, dropped on quit
    (events/listen! ::join PlayerJoinEvent {:priority :lowest}
                    (fn [^PlayerJoinEvent e] (apply-rank! (.getPlayer e))))
    (events/listen! ::quit PlayerQuitEvent {:priority :monitor}
                    (fn [^PlayerQuitEvent e] (clear-attachment! (.getUniqueId (.getPlayer e)))))
    ;; a reload: players already online get their permissions back once their rank is loaded.
    ;; The query queues behind the cache's own load on the same db thread, and callbacks reach
    ;; the main thread in order, so by then the rank is in the cache.
    (doseq [^Player player (Bukkit/getOnlinePlayers)]
      (db/query! (:db @state) "SELECT 1" [] (fn [_] (when (.isOnline player) (apply-rank! player)))))

    (cmd/defcmd rank {:permission "ranks.use" :description "Ranks do servidor"
                      :usage "/rank [list|up|set <jogador> <rank>]"
                      :complete (fn [sender args]
                                  (case (count args)
                                    1 (cmd/complete-prefix
                                       (cond-> ["list" "up"] (.hasPermission ^CommandSender sender "ranks.admin") (conj "set"))
                                       (first args))
                                    2 (when (= "set" (first args)) (cmd/complete-players (rest args)))
                                    3 (when (= "set" (first args))
                                        (cmd/complete-prefix (map :id (:ladder @state)) (nth args 2)))
                                    nil))}
      [sender args]
      (case (first args)
        nil (if (instance? Player sender) (info-command sender) (list-command sender))
        "list" (list-command sender)
        "up" (if (instance? Player sender) (up-command sender) (msg/send! sender "core.player-only"))
        "set" (if (.hasPermission ^CommandSender sender "ranks.admin")
                (set-command sender args)
                (msg/send! sender "core.no-permission"))
        false))

    ;; a separate command: an alias would not do, the typed label is ignored by the handler
    (cmd/defcmd rankup {:permission "ranks.use" :description "Compra o próximo rank"
                        :usage "/rankup" :player-only true}
      [sender _]
      (up-command sender))

    (services/provide! :ranks {:rank-of rank-of
                               :prefix-of prefix-of
                               :limit-of limit-of})
    (log/info (str "ranks enabled (" (count (:ladder @state)) " ranks)")))

  (disable [_]
    (services/release-all!)
    (doseq [uuid (keys @attachments)] (clear-attachment! uuid))
    (when-let [{:keys [db]} @state]
      (db/close! db))
    (reset! state nil)
    (log/info "ranks disabled")))
