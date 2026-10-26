(ns skycore.config
  "Config loading for every module. YAML is the format used for gameplay config
  (the Bukkit YAML parser is already on the classpath); EDN is available for
  data files. Maps use string keys, exactly as written in the file.

  A module's defaults ship inside its jar and are deep-merged under the
  admin's copy in the data folder, so new options appear after an update
  without touching the admin's file and code needs no fallback numbers.

  Loading reads the disk: do it in `enable` or on an explicit reload, never in
  a handler or tick."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [paperclj.core :as p])
  (:import (java.io File)
           (org.bukkit.configuration ConfigurationSection)
           (org.bukkit.configuration.file YamlConfiguration)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- pure parsing

(defn- ->clj [v]
  (cond
    (instance? ConfigurationSection v)
    (into {} (map (fn [[k x]] [k (->clj x)])) (.getValues ^ConfigurationSection v false))

    (instance? java.util.Map v)
    (into {} (map (fn [[k x]] [(str k) (->clj x)])) ^java.util.Map v)

    (instance? java.util.List v) (mapv ->clj ^java.util.List v)
    (instance? Integer v) (long ^Integer v)
    :else v))

(defn parse-yaml
  "YAML text -> nested map with string keys (blank text -> {})."
  [^String text]
  (let [yaml (YamlConfiguration.)]
    (.loadFromString yaml text)
    (->clj yaml)))

(defn parse-edn
  "EDN text -> data (blank text -> nil)."
  [^String text]
  (edn/read-string text))

(defn deep-merge
  "Merge nested maps; for anything that is not a map in both, b wins."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    b))

(defn split-path
  "\"grid.spacing\" -> [\"grid\" \"spacing\"]; a vector is returned as is."
  [path]
  (if (string? path) (str/split path #"\.") (vec path)))

(defn lookup
  "Value at `path` in `m`, or `not-found` when any step is missing."
  [m path not-found]
  (let [v (get-in m (split-path path) ::none)]
    (if (= v ::none) not-found v)))

;; ---------------------------------------------------------------- files

(defn load-edn [^File file]
  (when (.exists file)
    (parse-edn (slurp file))))

(defn- jar-text
  "Text of a resource inside the *current plugin's own* jar, or nil. Uses the
  plugin's own loader on purpose: the context loader would fall through to
  other plugins' jars."
  [^String resource]
  (when-let [in (.getResource (p/plugin) resource)]
    (with-open [in in]
      (slurp in))))

(defn load-yaml-resource
  "Defaults from the plugin's jar merged under the admin's copy in the data
  folder. The jar's copy is saved to the data folder when it is not there yet."
  [^String resource]
  (let [plugin (p/plugin)
        file (File. (.getDataFolder plugin) resource)
        defaults (some-> (jar-text resource) parse-yaml)]
    (when (and defaults (not (.exists file)))
      (.saveResource plugin resource false))
    (deep-merge (or defaults {})
                (if (.exists file) (parse-yaml (slurp file)) {}))))

;; ---------------------------------------------------------------- per-plugin store

(defonce ^:private store (atom {}))

(defn- plugin-name ^String [] (.getName (p/plugin)))

(defn load!
  "Load (or reload) a YAML resource of the current plugin and remember it.
  Returns the map."
  ([] (load! "config.yml"))
  ([^String resource]
   (let [m (load-yaml-resource resource)]
     (swap! store assoc [(plugin-name) resource] m)
     m)))

(defn conf
  "The loaded map of the current plugin (see load!)."
  ([] (conf "config.yml"))
  ([^String resource] (get @store [(plugin-name) resource])))

(defn option-of
  "Required option of another plugin's config.yml, e.g. (option-of \"skycore\" \"db.busy-timeout-ms\")."
  [^String plugin path]
  (let [v (lookup (get @store [plugin "config.yml"]) path ::none)]
    (when (= v ::none)
      (throw (ex-info (str "Missing config option '" path "' in " plugin "/config.yml")
                      {:plugin plugin :path path})))
    v))

(defn option
  "Option of the current plugin's config.yml by dotted path or vector. Without
  a default a missing option throws: defaults belong in the shipped config.yml."
  ([path] (option-of (plugin-name) path))
  ([path default] (lookup (conf) path default)))
