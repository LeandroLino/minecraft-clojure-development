(ns economy.core
  "Currency for the server: one whole-number balance per player.

  Balances live in memory while a player is online (skycore.cache loads them
  before the player joins), every change is written to SQLite on the db
  thread, and other modules reach all of it through the :economy service."
  (:require [economy.ledger :as ledger]
            [paperclj.core :as p]
            [skycore.cache :as cache]
            [skycore.cmd :as cmd]
            [skycore.config :as cfg]
            [skycore.db :as db]
            [skycore.log :as log]
            [skycore.msg :as msg]
            [skycore.services :as services])
  (:import (java.sql Connection)
           (java.util UUID)
           (org.bukkit Bukkit)
           (org.bukkit.command CommandSender)
           (org.bukkit.entity Player)))

(set! *warn-on-reflection* true)

(defonce ^:private state (atom nil)) ; {:db db :cache cache}

;; ---------------------------------------------------------------- config

(defn- max-balance ^long [] (long (cfg/option "max-balance")))

(defn format-money
  "An amount as the player sees it, e.g. \"$1.250\"."
  [n]
  (ledger/format-amount (str (cfg/option "currency.symbol")) n))

;; ---------------------------------------------------------------- accounts

(defn- load-account
  "Runs on the db thread: the player's row, created with the starting balance
  the first time."
  [^Connection conn ^UUID uuid]
  (if-let [row (first (db/query-sql conn "SELECT balance FROM accounts WHERE uuid = ?" [(str uuid)]))]
    {:balance (long (:balance row))}
    (let [start (long (cfg/option "starting-balance"))]
      (db/exec-sql conn "INSERT INTO accounts (uuid, balance) VALUES (?, ?)" [(str uuid) start])
      {:balance start})))

(defn balance
  "The online player's balance, or nil when their data is not loaded."
  [^UUID uuid]
  (:balance (cache/value (:cache @state) uuid)))

(defn- put-balance!
  "Set and persist a balance; false if the player's data is not loaded."
  [^UUID uuid new-balance]
  (if (cache/update! (:cache @state) uuid assoc :balance new-balance)
    (do (db/execute! (:db @state)
                     "INSERT OR REPLACE INTO accounts (uuid, balance) VALUES (?, ?)"
                     [(str uuid) new-balance] nil)
        true)
    false))

(defn- change!
  "Apply ledger function f to the balance: {:ok new} or {:error reason}."
  [^UUID uuid f]
  (if-let [current (balance uuid)]
    (let [result (f current)]
      (if-let [new-balance (:ok result)]
        (do (put-balance! uuid new-balance) result)
        result))
    {:error :not-loaded}))

(defn deposit! [^UUID uuid amount]
  (change! uuid #(ledger/deposit % amount (max-balance))))

(defn withdraw! [^UUID uuid amount]
  (change! uuid #(ledger/withdraw % amount)))

(defn transfer!
  "Move `amount` between two online players. {:ok {:from n :to n}} or
  {:error :insufficient | :overflow | :not-loaded | :invalid}."
  [^UUID from ^UUID to amount]
  (let [from-balance (balance from)
        to-balance (balance to)]
    (if (or (nil? from-balance) (nil? to-balance))
      {:error :not-loaded}
      (let [result (ledger/transfer from-balance to-balance amount (max-balance))]
        (when-let [{new-from :from new-to :to} (:ok result)]
          (put-balance! from new-from)
          (put-balance! to new-to))
        result))))

;; ---------------------------------------------------------------- commands

(defn- online-player ^Player [^String name]
  (Bukkit/getPlayerExact name))

(defn- not-found [sender name]
  (msg/send! sender "core.player-not-found" {:player name}))

(defn- balance-command [^CommandSender sender args]
  (cond
    (empty? args)
    (if (instance? Player sender)
      (msg/send! sender "economy.balance-self"
                 {:amount (format-money (or (balance (.getUniqueId ^Player sender)) 0))})
      (msg/send! sender "core.player-only"))

    (not (.hasPermission sender "economy.balance.others"))
    (msg/send! sender "core.no-permission")

    :else
    (if-let [target (online-player (first args))]
      (msg/send! sender "economy.balance-other"
                 {:player (.getName target)
                  :amount (format-money (or (balance (.getUniqueId target)) 0))})
      (not-found sender (first args)))))

(defn- pay-command [^Player sender args]
  (if (not= 2 (count args))
    false
    (let [target (online-player (first args))
          amount (ledger/parse-amount (second args))
          minimum (long (cfg/option "pay.min-amount"))]
      (cond
        (nil? target) (not-found sender (first args))
        (= (.getUniqueId target) (.getUniqueId sender)) (msg/send! sender "economy.pay-self")
        (nil? amount) (msg/send! sender "economy.invalid-amount" {:value (second args)})
        (< amount minimum) (msg/send! sender "economy.min-amount" {:amount (format-money minimum)})
        :else
        (let [result (transfer! (.getUniqueId sender) (.getUniqueId target) amount)]
          (case (:error result)
            nil (do (msg/send! sender "economy.pay-sent" {:amount (format-money amount) :player (.getName target)})
                    (msg/send! target "economy.pay-received" {:amount (format-money amount) :player (.getName sender)}))
            :insufficient (msg/send! sender "economy.insufficient"
                                     {:amount (format-money (balance (.getUniqueId sender)))})
            :overflow (msg/send! sender "economy.overflow" {:player (.getName target)})
            :not-loaded (msg/send! sender "economy.not-loaded" {:player (.getName target)})
            :invalid (msg/send! sender "economy.invalid-amount" {:value (second args)})))))))

(defn- eco-command [^CommandSender sender args]
  (let [[action player-name amount-text] args
        ^Player target (when player-name (online-player player-name))
        ;; set accepts 0, give and take need a positive amount
        amount (if (and (= "set" action) (= "0" amount-text)) 0 (ledger/parse-amount amount-text))]
    (cond
      (not (and (#{"give" "take" "set"} action) player-name amount-text (= 3 (count args))))
      false

      (nil? target) (not-found sender player-name)
      (nil? amount) (msg/send! sender "economy.invalid-amount" {:value amount-text})

      :else
      (let [uuid (.getUniqueId target)
            name (.getName target)
            result (case action
                     "give" (deposit! uuid amount)
                     "take" (withdraw! uuid amount)
                     "set" (if (> amount (max-balance))
                             {:error :overflow}
                             (do (put-balance! uuid amount) {:ok amount})))]
        (case (:error result)
          nil (msg/send! sender (str "economy.eco-" (case action "give" "given" "take" "taken" "set" "set"))
                         {:player name :amount (format-money amount) :balance (format-money (:ok result))})
          :insufficient (msg/send! sender "economy.eco-insufficient"
                                   {:player name :balance (format-money (balance uuid))})
          :overflow (msg/send! sender "economy.overflow" {:player name})
          :not-loaded (msg/send! sender "economy.not-loaded" {:player name})
          :invalid (msg/send! sender "economy.invalid-amount" {:value amount-text}))))))

;; ---------------------------------------------------------------- lifecycle

(p/defplugin
  (enable [_]
    (cfg/load!)
    (msg/load!)
    (let [d (db/open! (p/data-file "economy.db"))]
      (db/execute! d "CREATE TABLE IF NOT EXISTS accounts (uuid TEXT PRIMARY KEY, balance INTEGER NOT NULL)" [] nil)
      (reset! state {:db d :cache (cache/create! ::economy d load-account)}))

    (cmd/defcmd balance {:permission "economy.balance" :aliases ["bal" "money"]
                         :description "Mostra o saldo" :usage "/balance [jogador]"
                         :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-players args)))}
      [sender args] (balance-command sender args))

    (cmd/defcmd pay {:permission "economy.pay" :player-only true
                     :description "Envia moeda a outro jogador" :usage "/pay <jogador> <valor>"
                     :complete (fn [_ args] (when (= 1 (count args)) (cmd/complete-players args)))}
      [sender args] (pay-command sender args))

    (cmd/defcmd eco {:permission "economy.admin"
                     :description "Administra saldos" :usage "/eco <give|take|set> <jogador> <valor>"
                     :complete (fn [_ args]
                                 (case (count args)
                                   1 (cmd/complete-prefix ["give" "take" "set"] (first args))
                                   2 (cmd/complete-players args)
                                   nil))}
      [sender args] (eco-command sender args))

    (services/provide! :economy {:balance balance
                                 :deposit! deposit!
                                 :withdraw! withdraw!
                                 :transfer! transfer!
                                 :format format-money})
    (log/info "economy enabled"))

  (disable [_]
    (services/release-all!)
    (when-let [{:keys [db]} @state]
      (db/close! db))
    (reset! state nil)
    (log/info "economy disabled")))
