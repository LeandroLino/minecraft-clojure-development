(ns teleport.names
  "Names and limits for homes and warps, as pure functions."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn normalize
  "Names are case-insensitive: \"Casa\" and \"casa\" are the same home."
  [s]
  (str/lower-case (str s)))

(defn valid?
  "1 to `max-length` letters, digits, - or _."
  [s max-length]
  (boolean (and (string? s)
                (re-matches (re-pattern (str "[a-zA-Z0-9_-]{1," (long max-length) "}")) s))))

(defn can-set-home
  "Whether a player with `homes` (a map name -> location) and a `limit` may set
  home `name`: :new, :update (the name already exists, so no extra home) or
  :limit (a new home would pass the limit)."
  [homes name limit]
  (cond
    (contains? homes name) :update
    (>= (count homes) (long limit)) :limit
    :else :new))

(defn which-home
  "Which home /home should use. `requested` is the typed name or nil.
  Returns [:home name], [:unknown name], [:choose names] (several homes, none
  chosen) or [:none]."
  [homes requested default-name]
  (let [names (vec (sort (keys homes)))]
    (cond
      (some? requested) (let [n (normalize requested)]
                          (if (contains? homes n) [:home n] [:unknown n]))
      (empty? names) [:none]
      (contains? homes default-name) [:home default-name]
      (= 1 (count names)) [:home (first names)]
      :else [:choose names])))
