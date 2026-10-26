(ns skycore.services
  "Tiny registry so modules call each other without requiring each other's
  namespaces: economy publishes {:balance f ...}, shop looks it up. A module
  releases what it published in its `disable`."
  (:require [paperclj.core :as p]))

(set! *warn-on-reflection* true)

;; service key -> {:owner plugin-name :impl any}
(defonce ^:private registry (atom {}))

(defn- owner ^String [] (.getName (p/plugin)))

(defn- bind-to
  "Wrap each function of the impl map so it runs with `plugin` as the current
  plugin: the caller's thread is bound to the *caller's* plugin, and the
  provider's code reads its own config, messages and database."
  [plugin impl]
  (if (map? impl)
    (into {} (map (fn [[name f]]
                    [name (if (fn? f)
                            (fn [& args] (binding [p/*plugin* plugin] (apply f args)))
                            f)]))
          impl)
    impl))

(defn provide!
  "Publish `impl` (usually a map of functions) under keyword `k`. The functions
  run bound to the publishing plugin."
  [k impl]
  (swap! registry assoc k {:owner (owner) :impl (bind-to (p/plugin) impl)})
  k)

(defn service
  "The published implementation, or nil."
  [k]
  (:impl (get @registry k)))

(defn require-service
  "Like service, but throws when nobody published `k`."
  [k]
  (or (service k)
      (throw (ex-info (str "Service not available: " k) {:service k}))))

(defn release-all!
  "Remove every service published by the current plugin."
  []
  (let [me (owner)]
    (swap! registry (fn [r] (into {} (remove (fn [[_ v]] (= me (:owner v)))) r)))))
