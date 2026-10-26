(ns skycore.msg
  "Player-facing messages (i18n). Each module ships lang/<language>.yml with
  MiniMessage templates; nested YAML keys become dotted keys (\"core.usage\").
  {name} placeholders are filled from a params map and the values are
  MiniMessage-escaped, so a player name or chat text cannot inject formatting.
  {prefix} expands to the global prefix defined in skycore's language file.
  Lookup: the current module's table, then skycore's (common messages)."
  (:require [clojure.string :as str]
            [paperclj.core :as p]
            [skycore.config :as cfg]
            [skycore.log :as log])
  (:import (net.kyori.adventure.audience Audience)
           (net.kyori.adventure.text Component)
           (net.kyori.adventure.text.minimessage MiniMessage)
           (org.bukkit Bukkit)
           (org.bukkit.command CommandSender)))

(set! *warn-on-reflection* true)

;; The language every module falls back to when the configured one is missing.
(def ^:private fallback-language "pt-BR")
(def ^:private core-plugin "skycore")

;; Set by skycore on enable from its config.yml.
(defonce language (atom fallback-language))

;; plugin name -> {"dotted.key" "template"}
(defonce ^:private tables (atom {}))

;; ---------------------------------------------------------------- pure helpers

(defn flatten-keys
  "{\"a\" {\"b\" \"x\"}} -> {\"a.b\" \"x\"}. Non-string leaves are stringified."
  [m]
  (letfn [(walk [prefix v]
            (if (map? v)
              (mapcat (fn [[k x]] (walk (if prefix (str prefix "." k) (str k)) x)) v)
              [[prefix (str v)]]))]
    (into {} (mapcat (fn [[k v]] (walk (str k) v))) m)))

(defn fill
  "Replace {name} in `template` with (escape value) from `params` in a single
  pass; unknown placeholders are left as they are."
  [^String template params escape]
  (let [params (into {} (map (fn [[k v]] [(name k) v])) params)]
    (str/replace template #"\{([A-Za-z0-9_-]+)\}"
                 (fn [[whole k]]
                   (if (contains? params k)
                     (escape (str (get params k)))
                     whole)))))

;; ---------------------------------------------------------------- loading

(defn load!
  "Load lang/<language>.yml of the current plugin (falls back to pt-BR).
  Returns the number of messages loaded."
  []
  (let [resource (fn [lang] (str "lang/" lang ".yml"))
        wanted (cfg/load-yaml-resource (resource @language))
        table (if (seq wanted)
                wanted
                (do (log/warn (str "No messages for language '" @language "'; using " fallback-language))
                    (cfg/load-yaml-resource (resource fallback-language))))
        flat (flatten-keys table)]
    (swap! tables assoc (.getName (p/plugin)) flat)
    (count flat)))

;; ---------------------------------------------------------------- rendering

(defn- template-for [^String key]
  (or (get-in @tables [(.getName (p/plugin)) key])
      (get-in @tables [core-plugin key])))

(defn- escape-tags [^String s]
  (.escapeTags (MiniMessage/miniMessage) s))

(defn component
  "Render message `key` with `params` to an Adventure Component."
  (^Component [key] (component key {}))
  (^Component [^String key params]
   (if-let [^String template (template-for key)]
     (let [prefix (or (get-in @tables [core-plugin "prefix"]) "")
           text (fill (str/replace template "{prefix}" prefix) params escape-tags)]
       (.deserialize (MiniMessage/miniMessage) text))
     (do (log/warn (str "Missing message key: " key))
         (Component/text (str "[mensagem ausente: " key "]"))))))

(defn send!
  "Send message `key` to a command sender."
  ([^CommandSender sender key] (send! sender key {}))
  ([^CommandSender sender key params]
   (.sendMessage sender (component key params))))

(defn action-bar!
  "Show message `key` on the action bar (above the hotbar): for short feedback
  that would spam the chat if repeated."
  ([sender key] (action-bar! sender key {}))
  ([^Audience sender key params]
   (.sendActionBar sender (component key params))))

(defn broadcast!
  "Send message `key` to every online player and the console."
  ([key] (broadcast! key {}))
  ([key params]
   (Bukkit/broadcast (component key params))))
