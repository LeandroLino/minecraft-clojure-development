(ns admin.core
  "Admin tools: /sky status, backup, broadcast, fly, vanish, mute, unmute and heal, and the
  scheduled backup (the tools that act on players live in admin.players).

  A backup has two parts. On the main thread it saves the players and worlds
  and switches world autosave off, so the files stop changing under the copy.
  Then everything slow (SQLite snapshots, zipping, deleting old backups) runs
  on a worker thread with no Bukkit API, and the result comes back to the main
  thread, which switches autosave on again."
  (:require [admin.archive :as archive]
            [admin.players :as players]
            [admin.retention :as retention]
            [clojure.string :as str]
            [paperclj.core :as p]
            [skycore.cmd :as cmd]
            [skycore.config :as cfg]
            [skycore.db :as db]
            [skycore.log :as log]
            [skycore.msg :as msg]
            [skycore.sched :as sched]
            [skycore.services :as services])
  (:import (java.io File)
           (java.lang.management ManagementFactory)
           (java.nio.file Files)
           (java.time Instant ZoneId)
           (java.util.concurrent Future)
           (org.bukkit Bukkit World)
           (org.bukkit.command CommandSender)))

(set! *warn-on-reflection* true)

(defonce ^:private running? (atom false))
(defonce ^:private autosave-before (atom {}))  ; World -> its autosave flag before the backup
(defonce ^:private last-backup (atom nil))     ; {:name :bytes :at-ms}

;; ---------------------------------------------------------------- files

(defn- backup-dir ^File []
  (File. (Bukkit/getWorldContainer) ^String (cfg/option "backup.directory")))

(defn- check-backup-dir!
  "The backup folder must be a folder inside the server folder, not the server folder itself."
  []
  (let [root (.getCanonicalFile (Bukkit/getWorldContainer))
        dir (.getCanonicalFile (backup-dir))]
    (when-not (and (not= root dir) (.startsWith (.toPath dir) (.toPath root)))
      (throw (ex-info (str "Invalid admin config: backup.directory '" (cfg/option "backup.directory")
                           "' must be a folder inside the server folder") {})))))

(defn- list-backups
  "Backup files in the folder, newest first, as {:name :bytes :at-ms}. Reads the disk."
  [^File dir]
  (->> (or (.listFiles dir) (make-array File 0))
       (filter #(and (.isFile ^File %) (retention/backup-file? (.getName ^File %))))
       (map (fn [^File f] {:name (.getName f) :bytes (.length f) :at-ms (.lastModified f)}))
       (sort-by :name #(compare %2 %1))
       vec))

;; ---------------------------------------------------------------- backup job (worker thread)

(defn- snapshot-databases!
  "Copy every plugins/<module>/*.db to snap-root with VACUUM INTO: a consistent
  copy even while the module is writing. Returns the number of snapshots."
  [^File plugins-dir ^File snap-root]
  (let [dbs (for [^File dir (or (.listFiles plugins-dir) (make-array File 0))
                  :when (.isDirectory dir)
                  ^File f (or (.listFiles dir) (make-array File 0))
                  :when (str/ends-with? (.getName f) ".db")]
              [dir f])]
    (doseq [[^File dir ^File source] dbs]
      (let [out (File. snap-root (str (.getName dir) "/" (.getName source)))
            handle (db/open! source)]
        (.mkdirs (.getParentFile out))
        (try
          (.get ^Future (db/task! handle (fn [conn] (db/exec-sql conn "VACUUM INTO ?" [(.getAbsolutePath out)])) nil))
          (finally (db/close! handle)))
        (when-not (.exists out)
          (throw (ex-info (str "could not snapshot " source) {})))))
    (count dbs)))

(defn- data-file? [^String relative]
  (or (re-find #"\.db(-wal|-shm)?$" relative)   ; replaced by the snapshots
      (str/ends-with? relative ".jar")))         ; the plugins themselves are not data

(defn- delete-tree! [^File dir]
  (doseq [^File f (reverse (file-seq dir))] (.delete f)))

(defn- run-job!
  "Everything slow. `job` is plain data captured on the main thread."
  [{:keys [^File dir ^File plugins-dir world-folders include-plugin-data keep file-name]}]
  (let [started (System/nanoTime)
        snap-root (File. dir (str ".snapshots-" file-name))]
    (.mkdirs dir)
    (try
      (when include-plugin-data (snapshot-databases! plugins-dir snap-root))
      (let [sources (concat
                     (for [^File w world-folders]
                       {:root w :prefix (str "worlds/" (.getName w) "/") :skip? #(= "session.lock" %)})
                     (when include-plugin-data
                       [{:root plugins-dir :prefix "plugins/" :skip? data-file?}
                        {:root snap-root :prefix "plugins/"}]))
            result (archive/write-zip! (File. dir ^String file-name) sources)
            doomed (retention/to-delete (map #(:name %) (list-backups dir)) keep)]
        (doseq [n doomed] (Files/deleteIfExists (.toPath (File. dir ^String n))))
        (assoc result
               :name file-name
               :deleted (count doomed)
               :seconds (/ (Math/round (/ (- (System/nanoTime) started) 1e8)) 10.0)))
      (finally (delete-tree! snap-root)))))

;; ---------------------------------------------------------------- backup (main thread)

(defn- restore-autosave! []
  (doseq [[^World w before] @autosave-before]
    (try (.setAutoSave w (boolean before)) (catch Throwable t (log/error t "could not restore world autosave"))))
  (reset! autosave-before {}))

(defn- finish! []
  (restore-autosave!)
  (reset! running? false))

(defn- backup!
  "Start a backup; messages go to `initiator`."
  [^CommandSender initiator]
  (if-not (compare-and-set! running? false true)
    (msg/send! initiator "admin.backup-running")
    (try
      (msg/send! initiator "admin.backup-started")
      (Bukkit/savePlayers)
      (let [worlds (Bukkit/getWorlds)]
        (doseq [^World w worlds]
          ;; autosave goes off before the manual save so no periodic save interleaves with it. Paper
          ;; still logs one WARN per run for any manual save while the server-wide autosave is
          ;; configured (CraftServer.checkSaveState); it is informational and cannot be avoided here.
          (swap! autosave-before assoc w (.isAutoSave w))
          (.setAutoSave w false)
          (.save w))
        (let [job {:dir (backup-dir)
                   :plugins-dir (.getParentFile (.getDataFolder (p/plugin)))
                   :world-folders (archive/outermost (map #(archive/level-root (.getWorldFolder ^World %) (Bukkit/getWorldContainer)) worlds))
                   :include-plugin-data (boolean (cfg/option "backup.include-plugin-data"))
                   :keep (long (cfg/option "backup.keep"))
                   :file-name (retention/backup-name (Instant/now) (ZoneId/systemDefault))}]
          (sched/async-then
           #(run-job! job)
           (fn [{:keys [name bytes files skipped seconds] :as result}]
             (finish!)
             (reset! last-backup {:name name :bytes bytes :at-ms (System/currentTimeMillis)})
             (log/info (str "backup " name " done (" files " files, " (retention/format-size bytes) ", " seconds "s)"))
             (msg/send! initiator "admin.backup-done" {:file name :size (retention/format-size bytes)
                                                       :files files :seconds seconds})
             (when (pos? skipped) (msg/send! initiator "admin.backup-skipped-files" {:count skipped})))
           (fn [^Throwable t]
             (finish!)
             (msg/send! initiator "admin.backup-failed" {:reason (or (.getMessage t) (str (class t)))})))))
      (catch Throwable t
        (finish!)
        (log/error t "backup could not start")
        (msg/send! initiator "admin.backup-failed" {:reason (or (.getMessage t) (str (class t)))})))))

(defn- backup-list! [^CommandSender sender]
  (let [dir (backup-dir)
        limit (long (cfg/option "backup.list-limit"))]
    (sched/async-then
     #(list-backups dir)
     (fn [files]
       (if (empty? files)
         (msg/send! sender "admin.backup-list-none")
         (do (msg/send! sender "admin.backup-list-header" {:count (count files)})
             (doseq [{:keys [name bytes]} (take limit files)]
               (msg/send! sender "admin.backup-list-line" {:file name :size (retention/format-size bytes)})))))
     (fn [_] (msg/send! sender "admin.backup-list-error")))))

;; ---------------------------------------------------------------- /sky

(defn- status! [^CommandSender sender]
  (let [rt (Runtime/getRuntime)
        mb (fn [n] (quot (long n) 1048576))
        uptime-s (quot (.getUptime (ManagementFactory/getRuntimeMXBean)) 1000)
        last @last-backup]
    (msg/send! sender "admin.status-header")
    (msg/send! sender "admin.status-tps" {:tps (retention/format-tps (Bukkit/getTPS))
                                          :mspt (String/format java.util.Locale/ROOT "%.1f" (object-array [(Bukkit/getAverageTickTime)]))})
    (msg/send! sender "admin.status-memory" {:used (mb (- (.totalMemory rt) (.freeMemory rt))) :max (mb (.maxMemory rt))})
    (msg/send! sender "admin.status-players" {:online (count (Bukkit/getOnlinePlayers)) :max (Bukkit/getMaxPlayers)})
    (msg/send! sender "admin.status-uptime" {:uptime (retention/format-duration uptime-s)})
    (doseq [^World w (Bukkit/getWorlds)]
      (msg/send! sender "admin.status-world" {:world (.getName w) :players (.getPlayerCount w)
                                              :chunks (.getChunkCount w) :entities (.getEntityCount w)}))
    (msg/send! sender "admin.status-backup"
               {:backup (if last
                          (str (:name last) " (há " (retention/format-duration (quot (- (System/currentTimeMillis) (:at-ms last)) 1000))
                               ", " (retention/format-size (:bytes last)) ")")
                          "nenhum")})))

(defn- senha!
  "/sky senha <player> <new password>: give a player's account a new password (the auth module
  checks the length and does the hashing, off the main thread). The answer arrives later, on the
  main thread, so the callback keeps this plugin bound to find its messages."
  [^CommandSender sender args]
  (if (not= 2 (count args))
    false
    (if-let [auth (services/service :auth)]
      (let [player-name (first args)]
        ((:set-password! auth) player-name (second args)
         (bound-fn [result]
           (case result
             :ok (msg/send! sender "admin.senha-ok" {:player player-name})
             :no-account (msg/send! sender "admin.senha-no-account" {:player player-name})
             :too-short (msg/send! sender "admin.senha-too-short")
             :too-long (msg/send! sender "admin.senha-too-long")
             (msg/send! sender "core.error")))))
      (msg/send! sender "admin.senha-no-auth"))))

(defn- sky-command [^CommandSender sender args]
  (let [allowed? (fn [permission] (or (.hasPermission sender ^String permission)
                                      (do (msg/send! sender "core.no-permission") false)))]
    (case (first args)
      "status" (when (allowed? "admin.status") (status! sender))
      "backup" (when (allowed? "admin.backup")
                 (case (second args)
                   "now" (backup! sender)
                   "list" (backup-list! sender)
                   false))
      "broadcast" (when (allowed? "admin.broadcast")
                    (if (< (count args) 2)
                      false
                      (Bukkit/broadcast (msg/component "admin.broadcast" {:message (str/join " " (rest args))}))))
      "fly" (when (allowed? "admin.fly") (if (> (count args) 2) false (players/fly! sender (rest args))))
      "vanish" (when (allowed? "admin.vanish") (if (> (count args) 2) false (players/vanish! sender (rest args))))
      "heal" (when (allowed? "admin.heal") (if (> (count args) 2) false (players/heal! sender (rest args))))
      "mute" (when (allowed? "admin.mute") (players/mute! sender (rest args)))
      "unmute" (when (allowed? "admin.mute") (players/unmute! sender (rest args)))
      "senha" (when (allowed? "admin.senha") (senha! sender (rest args)))
      false)))

(def ^:private subcommands
  "Subcommand -> the permission it needs, in the order they are suggested."
  [["status" "admin.status"] ["backup" "admin.backup"] ["broadcast" "admin.broadcast"]
   ["fly" "admin.fly"] ["heal" "admin.heal"] ["vanish" "admin.vanish"] ["mute" "admin.mute"] ["unmute" "admin.mute"]
   ["senha" "admin.senha"]])

;; ---------------------------------------------------------------- lifecycle

(p/defplugin
  (enable [_]
    (cfg/load!)
    (msg/load!)
    (check-backup-dir!)
    (doseq [k ["backup.interval-minutes" "backup.keep" "backup.list-limit"]]
      (let [v (cfg/option k)]
        (when-not (and (integer? v) (pos? (long v)))
          (throw (ex-info (str "Invalid admin config: " k " must be a whole number > 0, got " (pr-str v)) {})))))

    (cmd/defcmd sky {:description "Ferramentas de administração"
                     :usage "/sky <status|backup now|backup list|broadcast <mensagem>|fly [jogador]|heal [jogador]|vanish [jogador]|mute <jogador> [minutos]|unmute <jogador>|senha <jogador> <nova>>"
                     :complete (fn [sender args]
                                 (case (count args)
                                   1 (cmd/complete-prefix
                                      (for [[sub permission] subcommands
                                            :when (.hasPermission ^CommandSender sender ^String permission)]
                                        sub)
                                      (first args))
                                   2 (cond
                                       (and (= "backup" (first args)) (.hasPermission ^CommandSender sender "admin.backup"))
                                       (cmd/complete-prefix ["now" "list"] (second args))

                                       (some (fn [[sub permission]]
                                               (and (= sub (first args)) (#{"fly" "heal" "vanish" "mute" "unmute" "senha"} sub)
                                                    (.hasPermission ^CommandSender sender ^String permission)))
                                             subcommands)
                                       (cmd/complete-players (rest args)))
                                   nil))}
      [sender args] (sky-command sender args))

    (players/install!)
    (services/provide! :admin {:vanished? players/vanished?})
    (reset! running? false)
    (reset! autosave-before {})
    ;; learn when the last backup was made, without touching the disk on the main thread
    (let [dir (backup-dir)]
      (sched/async-then #(first (list-backups dir))
                        (fn [newest] (when newest (reset! last-backup newest)))))

    (if (cfg/option "backup.enabled")
      (let [period (* 1200 (long (cfg/option "backup.interval-minutes")))] ; 1200 ticks = 1 minute
        (p/run-repeating! ::backup period period #(backup! (Bukkit/getConsoleSender)))
        (log/info (str "admin enabled (backup every " (cfg/option "backup.interval-minutes") " min, keeping "
                       (cfg/option "backup.keep") ")")))
      (log/info "admin enabled (scheduled backups are off)")))

  (disable [_]
    (services/release-all!)
    (players/uninstall!)
    (restore-autosave!)
    (reset! running? false)
    (log/info "admin disabled")))
