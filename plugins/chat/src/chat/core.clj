(ns chat.core
  "Chat format, join/quit and welcome messages, tab list and sidebar scoreboard.

  The settings are copied into plain data at enable. Chat rendering runs on
  Paper's async chat thread, where no plugin is bound, so it must not read the
  plugin config or messages; it only uses `settings` and the thread-safe
  :ranks service."
  (:require [chat.death :as death]
            [chat.render :as render]
            [paperclj.core :as p]
            [skycore.config :as cfg]
            [skycore.events :as events]
            [skycore.log :as log]
            [skycore.services :as services])
  (:import (io.papermc.paper.chat ChatRenderer ChatRenderer$ViewerUnaware)
           (io.papermc.paper.event.player AsyncChatEvent)
           (io.papermc.paper.scoreboard.numbers NumberFormat)
           (java.time Duration)
           (java.util UUID)
           (net.kyori.adventure.text Component)
           (net.kyori.adventure.title Title Title$Times)
           (org.bukkit Bukkit)
           (org.bukkit.entity Entity Player)
           (org.bukkit.event.entity PlayerDeathEvent)
           (org.bukkit.event.player PlayerJoinEvent PlayerQuitEvent)
           (org.bukkit.scoreboard Criteria DisplaySlot Objective Scoreboard)))

(set! *warn-on-reflection* true)

(def ^:private max-scoreboard-lines 15) ; a Minecraft sidebar shows at most 15 rows

(defonce ^:private settings (atom nil))   ; the config map, string keys
(defonce ^:private boards (atom {}))      ; uuid -> {:scoreboard :objective :shown [Component]}

(defn- conf [path] (cfg/lookup @settings path nil))

;; ---------------------------------------------------------------- placeholder values

(defn- visible-online-count
  "Players online, leaving out the vanished ones (the :admin service knows who they are)."
  []
  (let [hidden? (or (some-> (services/service :admin) :vanished?) (constantly false))]
    (render/count-visible (map #(.getUniqueId ^Player %) (Bukkit/getOnlinePlayers)) hidden?)))

(defn- player-values
  "Placeholder values for a player; safe on any thread."
  [^Player player]
  (let [uuid (.getUniqueId player)
        ranks (services/service :ranks)
        eco (services/service :economy)
        rank (when ranks ((:rank-of ranks) uuid))
        balance (when eco ((:balance eco) uuid))
        tps (first (Bukkit/getTPS))]
    {:parsed {:prefix (or (:prefix rank) "")}
     :text {:player (.getName player)
            :rank (or (:name rank) "-")
            :balance (if (and eco balance) ((:format eco) balance) "-")
            :online (visible-online-count)
            :max (Bukkit/getMaxPlayers)
            :tps (render/tps-text tps)}}))

;; ---------------------------------------------------------------- chat

(defn- render-chat [^Player source ^Component display-name ^Component message]
  (let [values (player-values source)]
    (render/render (conf "chat.format")
                   (-> values
                       (assoc-in [:component :player] display-name)
                       (update :text dissoc :player)
                       (assoc-in [:component :message] message)))))

(defn- on-chat [^AsyncChatEvent e]
  (.renderer e (ChatRenderer/viewerUnaware
                (reify ChatRenderer$ViewerUnaware
                  (render [_ source display-name message]
                    (render-chat source display-name message))))))

;; ---------------------------------------------------------------- death messages

(defn- killer-of
  "Who was behind the death: [:player entity], [:mob entity] or nil."
  [^Player victim ^PlayerDeathEvent e]
  (let [^Entity cause (.getCausingEntity (.getDamageSource e))
        ^Player killer (.getKiller victim)]
    (cond
      (and cause (instance? Player cause) (not= cause victim)) [:player cause]
      killer [:player killer]
      (and cause (not= cause victim)) [:mob cause])))

(defn- on-death
  "Replace the game's death message with one of ours. Runs before plugins that look at the death at MONITOR.
  A vanished admin dies without anyone being told."
  [^PlayerDeathEvent e]
  (let [^Player victim (.getEntity e)
        hidden? (or (some-> (services/service :admin) :vanished?) (constantly false))]
    (if (hidden? (.getUniqueId victim))
      (.deathMessage e nil)
      (let [[kind ^Entity killer] (killer-of victim e)
            damage-type (some-> (.getDamageSource e) .getDamageType .getKey .getKey)
            category (death/category damage-type kind)]
        (.deathMessage e (death/message (conf "death.messages") category (conf "death.fallback")
                                        (.getName victim) (some-> killer .getName)
                                        (fn [n] (rand-int n))))))))

;; ---------------------------------------------------------------- join, quit, welcome

(defn- ticks->duration ^Duration [ticks] (Duration/ofMillis (* 50 (long ticks))))

(defn- welcome! [^Player player values]
  (doseq [line (conf "welcome.lines")]
    (.sendMessage player (render/render line values)))
  (.showTitle player
              (Title/title (render/render (conf "welcome.title") values)
                           (render/render (conf "welcome.subtitle") values)
                           (Title$Times/times (ticks->duration (conf "welcome.title-fade-in-ticks"))
                                              (ticks->duration (conf "welcome.title-stay-ticks"))
                                              (ticks->duration (conf "welcome.title-fade-out-ticks"))))))

;; ---------------------------------------------------------------- tab list

(defn- update-tab! [^Player player]
  (let [values (player-values player)]
    (.sendPlayerListHeaderAndFooter player
                                    (render/render (conf "tab.header") values)
                                    (render/render (conf "tab.footer") values))
    (.playerListName player (render/render (conf "tab.name-format") values))))

;; ---------------------------------------------------------------- sidebar scoreboard

(defn- new-board! [^Player player]
  (let [scoreboard (.getNewScoreboard (Bukkit/getScoreboardManager))
        objective (.registerNewObjective scoreboard "sidebar" Criteria/DUMMY
                                         (render/render (conf "scoreboard.title") (player-values player)))]
    (.setDisplaySlot objective DisplaySlot/SIDEBAR)
    (.numberFormat objective (NumberFormat/blank))   ; show text only, not the score numbers
    (.setScoreboard player scoreboard)
    {:scoreboard scoreboard :objective objective :shown []}))

(defn- update-board!
  "Redraw the sidebar, touching only the rows whose text changed."
  [^Player player]
  (let [uuid (.getUniqueId player)
        {:keys [^Objective objective shown] :as board} (or (get @boards uuid) (new-board! player))
        values (player-values player)
        templates (conf "scoreboard.lines")
        rows (count templates)
        lines (mapv #(render/render % values) templates)]
    (doseq [[i ^Component line] (map-indexed vector lines)
            :when (not= line (get shown i))]
      (let [score (.getScore objective (render/scoreboard-entry i))]
        (.setScore score (- rows i))                  ; a higher score is a higher row
        (.customName score line)))
    ;; the template list is fixed at enable, so rows never need removing
    (swap! boards assoc uuid (assoc board :shown lines))))

(defn- drop-board! [^Player player]
  (swap! boards dissoc (.getUniqueId player)))

;; ---------------------------------------------------------------- per player

(defn- setup-player! [^Player player]
  (when (conf "tab.enabled") (update-tab! player))
  (when (conf "scoreboard.enabled") (update-board! player)))

(defn- on-join [^PlayerJoinEvent e]
  (let [player (.getPlayer e)
        values (player-values player)]
    (.joinMessage e (render/render (conf (if (.hasPlayedBefore player) "join.message" "join.first-message")) values))
    (welcome! player values)
    (setup-player! player)))

(defn- on-quit [^PlayerQuitEvent e]
  (let [player (.getPlayer e)]
    (.quitMessage e (render/render (conf "quit.message") (player-values player)))
    (drop-board! player)))

;; ---------------------------------------------------------------- lifecycle

(defn- check-config! []
  (let [rows (count (conf "scoreboard.lines"))]
    (when (> rows max-scoreboard-lines)
      (throw (ex-info (str "Invalid chat config: scoreboard.lines has " rows " lines, the sidebar shows at most "
                           max-scoreboard-lines) {})))
    (doseq [k ["tab.refresh-seconds" "scoreboard.refresh-seconds"]]
      (let [v (conf k)]
        (when-not (and (integer? v) (pos? (long v)))
          (throw (ex-info (str "Invalid chat config: " k " must be a whole number > 0, got " (pr-str v)) {})))))))

(p/defplugin
  (enable [_]
    (cfg/load!)
    (reset! settings (cfg/conf))
    (check-config!)

    (events/listen! ::chat AsyncChatEvent {:priority :high :ignore-cancelled true} on-chat)
    (events/listen! ::join PlayerJoinEvent {:priority :high} on-join)
    (events/listen! ::quit PlayerQuitEvent {:priority :high} on-quit)
    (when (conf "death.enabled")
      (events/listen! ::death PlayerDeathEvent {:priority :high} on-death))

    (when (conf "tab.enabled")
      (let [period (* 20 (long (conf "tab.refresh-seconds")))]
        (p/run-repeating! ::tab period period
                          (fn [] (doseq [^Player pl (Bukkit/getOnlinePlayers)] (update-tab! pl))))))
    (when (conf "scoreboard.enabled")
      (let [period (* 20 (long (conf "scoreboard.refresh-seconds")))]
        (p/run-repeating! ::board period period
                          (fn [] (doseq [^Player pl (Bukkit/getOnlinePlayers)] (update-board! pl))))))
    ;; a reload: players already online get their tab and sidebar right away
    (doseq [^Player pl (Bukkit/getOnlinePlayers)] (setup-player! pl))
    (log/info "chat enabled"))

  (disable [_]
    (let [main (.getMainScoreboard (Bukkit/getScoreboardManager))]
      (doseq [^Player pl (Bukkit/getOnlinePlayers)]
        (when (contains? @boards (.getUniqueId pl)) (.setScoreboard pl main))))
    (reset! boards {})
    (log/info "chat disabled")))
