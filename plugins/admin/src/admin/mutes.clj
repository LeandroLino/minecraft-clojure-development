(ns admin.mutes
  "Mute durations and expiry as pure functions: time always comes in as an
  argument. A mute entry is {:name \"Ana\" :until millis-or-nil}; a nil :until
  is a permanent mute. In the database a permanent mute is stored as 0.")

(set! *warn-on-reflection* true)

(defn parse-duration
  "Minutes typed by an admin: nil (nothing typed) is permanent; digits are a
  number of minutes (1 to 9 digits, at least 1). Returns [:permanent],
  [:minutes n], or nil when the text is not a valid duration."
  [text]
  (cond
    (nil? text) [:permanent]
    (re-matches #"[0-9]{1,9}" text) (let [n (Long/parseLong text)]
                                      (when (pos? n) [:minutes n]))
    :else nil))

(defn until-millis
  "When a mute started at `now` ends: nil for a permanent one."
  [now [kind minutes]]
  (when (= :minutes kind)
    (+ (long now) (* 60000 (long minutes)))))

(defn active?
  "Whether the entry still silences the player at time `now`."
  [{:keys [until]} now]
  (or (nil? until) (< (long now) (long until))))

(defn remaining-minutes
  "Whole minutes left, rounded up (never 0 while the mute is active), or nil for a permanent mute."
  [{:keys [until]} now]
  (when until
    (max 1 (long (Math/ceil (/ (- (long until) (long now)) 60000.0))))))

(defn ->db-until
  "The :until of an entry as stored in the database."
  [{:keys [until]}]
  (or until 0))

(defn <-db-until
  "The :until stored in the database as an entry's :until."
  [stored]
  (let [n (long stored)]
    (when (pos? n) n)))
