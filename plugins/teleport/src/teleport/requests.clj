(ns teleport.requests
  "Pending /tpa and /tpahere requests as a pure value: a map of
  [from to] -> {:type :tpa|:tpahere, :at millis}. Time is always passed in, so
  nothing here reads a clock. A new request from the same player to the same
  target replaces the old one.")

(set! *warn-on-reflection* true)

(def empty-requests {})

(defn add
  "Record a request from `from` to `to` (player ids) made at time `now`."
  [requests from to type now]
  (assoc requests [from to] {:type type :at now}))

(defn expired?
  [{:keys [at]} now ttl-ms]
  (>= (- now at) ttl-ms))

(defn prune
  "Drop every request older than `ttl-ms`."
  [requests now ttl-ms]
  (into {} (remove (fn [[_ r]] (expired? r now ttl-ms))) requests))

(defn for-target
  "The live requests addressed to `to`, newest first, as [{:from :type :at}]."
  [requests to now ttl-ms]
  (->> requests
       (keep (fn [[[from target] r]]
               (when (and (= target to) (not (expired? r now ttl-ms)))
                 (assoc r :from from))))
       (sort-by :at >)
       vec))

(defn pick
  "The request /tpaccept or /tpdeny acts on: the one from `from` when given,
  else the newest. nil when there is none."
  [requests to from now ttl-ms]
  (let [live (for-target requests to now ttl-ms)]
    (if from
      (first (filter #(= from (:from %)) live))
      (first live))))

(defn remove-request
  "Remove the request from `from` to `to`."
  [requests from to]
  (dissoc requests [from to]))

(defn cancel-from
  "Remove every request made by `from`. Returns [requests' removed-count]."
  [requests from]
  (let [kept (into {} (remove (fn [[[f _] _]] (= f from))) requests)]
    [kept (- (count requests) (count kept))]))

(defn forget-player
  "Remove every request involving the player, as sender or target (on quit)."
  [requests player]
  (into {} (remove (fn [[[f t] _]] (or (= f player) (= t player)))) requests))
