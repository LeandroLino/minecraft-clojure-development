(ns skycore.cmd
  "Commands for the modules, on top of paperclj's register-command!:
  pt-BR permission/usage/error messages, a player-only guard, exceptions
  logged instead of leaking, and tab-completion helpers."
  (:require [clojure.string :as str]
            [paperclj.core :as p]
            [skycore.log :as log]
            [skycore.msg :as msg])
  (:import (org.bukkit Bukkit)
           (org.bukkit.entity Player)))

(set! *warn-on-reflection* true)

(defn complete-prefix
  "Candidates that start with `prefix`, case-insensitive, sorted."
  [candidates ^String prefix]
  (let [prefix (str/lower-case prefix)]
    (vec (sort-by str/lower-case (filter #(str/starts-with? (str/lower-case %) prefix) candidates)))))

(defn online-player-names []
  (mapv #(.getName ^Player %) (Bukkit/getOnlinePlayers)))

(defn complete-players
  "Tab-completion candidates for the last argument being an online player."
  [args]
  (complete-prefix (online-player-names) (or (peek args) "")))

(defn register!
  "Register command `name`. Options: :permission, :aliases, :description,
  :usage (shown with core.usage when the handler returns false), :complete
  (fn [sender args]) and :player-only. `handler` is (fn [sender args]).
  Load the module's messages (skycore.msg/load!) before registering."
  [^String name {:keys [permission usage player-only complete] :as opts} handler]
  (let [usage (or usage (str "/" name))]
    (p/register-command!
     name
     (cond-> (-> opts
                 (dissoc :player-only)
                 (assoc :usage usage))
       permission (assoc :permission-message (msg/component "core.no-permission"))
       complete (assoc :complete (fn [sender args]
                                   (try
                                     (complete sender args)
                                     (catch Throwable t
                                       (log/error t (str "tab-complete of /" name " failed"))
                                       [])))))
     (fn [sender args]
       (try
         (cond
           (and player-only (not (instance? Player sender)))
           (msg/send! sender "core.player-only")

           (false? (handler sender args))
           (msg/send! sender "core.usage" {:usage usage}))
         (catch Throwable t
           (log/error t (str "command /" name " failed"))
           (msg/send! sender "core.error")))
       true))))

(defmacro defcmd
  "(defcmd pay {:permission \"economy.pay\" :player-only true} [sender args] ...)
  Registers /pay when evaluated (place it in the enable body). The options map
  may be omitted."
  [cmd-name & more]
  (let [[opts [[sender args] & body]] (if (map? (first more))
                                        [(first more) (rest more)]
                                        [{} more])]
    `(register! ~(name cmd-name) ~opts (fn [~sender ~args] ~@body))))
