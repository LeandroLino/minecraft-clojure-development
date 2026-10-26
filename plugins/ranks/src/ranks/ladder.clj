(ns ranks.ladder
  "The rank ladder as pure data: parsing and checking the config, finding the
  next rank, and working out what a rank grants (its own permissions and
  limits plus everything inherited from the ranks below it)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- parse-rank [fail! i r]
  (let [where (str "ranks." i)
        id (get r "id")
        price (get r "price")
        perms (get r "permissions" [])
        limits (get r "limits" {})]
    (when-not (and (string? id) (re-matches #"[a-z0-9_-]{1,32}" id))
      (fail! (str where ".id: must be 1-32 lowercase letters, digits, - or _, got " (pr-str id))))
    (when-not (and (integer? price) (>= (long price) 0))
      (fail! (str where ".price: must be a whole number >= 0, got " (pr-str price))))
    (when-not (and (sequential? perms) (every? string? perms))
      (fail! (str where ".permissions: must be a list of permission names")))
    (when-not (and (map? limits) (every? #(and (string? (key %)) (number? (val %))) limits))
      (fail! (str where ".limits: must map names to numbers")))
    {:id id
     :name (str (get r "name" id))
     :prefix (str (get r "prefix" ""))
     :price (when (integer? price) (long price))
     :permissions (if (sequential? perms) (vec perms) [])
     :limits (if (map? limits) limits {})}))

(defn parse
  "Turn the `ranks` list of the config (string keys) into the ladder: a vector
  of {:id :name :prefix :price :permissions :limits}. Throws ex-info listing
  every problem."
  [cfg-ranks]
  (let [errors (atom [])
        fail! (fn [msg] (swap! errors conj msg) nil)
        ladder (if (and (sequential? cfg-ranks) (seq cfg-ranks))
                 (vec (map-indexed #(parse-rank fail! %1 %2) cfg-ranks))
                 (do (fail! "ranks: must be a non-empty list") []))
        ids (map :id ladder)]
    (when-not (or (empty? ids) (apply distinct? ids))
      (fail! (str "ranks: duplicate ids " (pr-str (vec (for [[k n] (frequencies ids) :when (> n 1)] k))))))
    (when (and (seq ladder) (not= 0 (:price (first ladder))))
      (fail! "ranks.0.price: the first rank is what everyone starts with and must cost 0"))
    (when (seq @errors)
      (throw (ex-info (str "Invalid ranks config:\n - " (str/join "\n - " @errors)) {:errors @errors})))
    ladder))

(defn position
  "Index of the rank with this id, or nil."
  [ladder id]
  (first (keep-indexed (fn [i r] (when (= id (:id r)) i)) ladder)))

(defn find-rank [ladder id]
  (some #(when (= id (:id %)) %) ladder))

(defn next-rank
  "The rank after `id`, or nil at the top (or for an unknown id)."
  [ladder id]
  (when-let [i (position ladder id)]
    (get ladder (inc i))))

(defn effective
  "What the rank `id` grants: {:permissions #{...} :limits {name number}},
  inheriting from every rank below it. The first rank when `id` is unknown."
  [ladder id]
  (let [i (or (position ladder id) 0)
        upto (subvec ladder 0 (inc i))]
    {:permissions (into #{} (mapcat :permissions) upto)
     :limits (reduce merge {} (map :limits upto))}))

(defn limit
  "A numeric limit (by name, string or keyword) for the rank, or nil."
  [ladder id limit-name]
  (get-in (effective ladder id) [:limits (name limit-name)]))
