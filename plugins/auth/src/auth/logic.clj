(ns auth.logic
  "The rules of the login as pure functions: password length, which commands an unlogged player
  may use, how many wrong passwords they get, the time limit, and the 5 minutes during which a
  player who comes back from the same address is not asked again. No Bukkit in here."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- passwords

(defn password-error
  "nil when `password` has an acceptable length, else :too-short or :too-long."
  [^String password min-length max-length]
  (let [n (count password)]
    (cond (< n (long min-length)) :too-short
          (> n (long max-length)) :too-long)))

;; ---------------------------------------------------------------- commands

(def ^:private allowed-labels #{"login" "register" "auth:login" "auth:register"})

(defn command-label
  "The command of a chat line as typed after the slash, lower case and without arguments:
  \"/Login abc\" -> \"login\". Blank for an empty line."
  [^String message]
  (-> (or message "") str/trim (str/replace-first #"^/+" "") (str/split #"\s+" 2) first (or "") str/lower-case))

(defn allowed-command?
  "Whether an unlogged player may run this chat line: only /login and /register (also written with
  the auth: prefix). Anything else, including the other plugins' own prefixed forms, is refused."
  [^String message]
  (contains? allowed-labels (command-label message)))

;; ---------------------------------------------------------------- attempts and time

(defn wrong-password
  "A wrong password after `attempts` mistakes: {:attempts n :left how many tries remain :kick? bool}."
  [attempts max-attempts]
  (let [n (inc (long attempts))
        left (max 0 (- (long max-attempts) n))]
    {:attempts n :left left :kick? (>= n (long max-attempts))}))

(defn seconds-left
  "Whole seconds a player still has to log in (never negative)."
  [joined-ms now-ms timeout-seconds]
  (max 0 (long (Math/ceil (/ (- (+ (long joined-ms) (* 1000 (long timeout-seconds))) (long now-ms)) 1000.0)))))

(defn timed-out?
  "Whether the time to log in has run out."
  [joined-ms now-ms timeout-seconds]
  (>= (- (long now-ms) (long joined-ms)) (* 1000 (long timeout-seconds))))

;; ---------------------------------------------------------------- remembering an address

(defn remember
  "`remembered` (uuid -> {:ip :until-ms}) with `uuid` remembered at address `ip` for `minutes`."
  [remembered uuid ip now-ms minutes]
  (assoc remembered uuid {:ip ip :until-ms (+ (long now-ms) (* 60000 (long minutes)))}))

(defn remembered?
  "Whether `uuid` coming from `ip` at `now-ms` is still remembered: the same address, in time."
  [remembered uuid ip now-ms]
  (let [{saved-ip :ip until :until-ms} (get remembered uuid)]
    (boolean (and saved-ip ip (= saved-ip ip) (< (long now-ms) (long until))))))

(defn prune
  "`remembered` without the entries that ran out."
  [remembered now-ms]
  (into {} (filter (fn [[_ {:keys [until-ms]}]] (< (long now-ms) (long until-ms)))) remembered))
