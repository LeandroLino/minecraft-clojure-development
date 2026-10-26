(ns admin.retention
  "Backup file names, which ones to delete, and size text. Pure: time and
  names come in as arguments."
  (:require [clojure.string :as str])
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def ^:private ^DateTimeFormatter stamp-format
  (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn backup-name
  "backup-20261022-093456.zip for this instant in this zone. The stamp sorts
  the same alphabetically and in time."
  ^String [^Instant instant ^ZoneId zone]
  (str "backup-" (.format (.withZone stamp-format zone) instant) ".zip"))

(defn backup-file?
  "Whether a file name was made by backup-name. Only these are ever deleted."
  [^String name]
  (boolean (re-matches #"backup-\d{8}-\d{6}\.zip" (str name))))

(defn to-delete
  "Of these file names, the backups beyond the newest `keep`, oldest first.
  Anything that is not a backup file name is never returned."
  [names keep]
  (let [backups (sort (filter backup-file? names))        ; oldest first
        excess (- (count backups) (max 0 (long keep)))]
    (vec (take (max 0 excess) backups))))

(defn format-size
  "Bytes as text with one decimal: 1536 -> \"1,5 KB\" (pt-BR separator)."
  [bytes]
  (let [b (double bytes)
        [value unit] (cond (>= b 1073741824.0) [(/ b 1073741824.0) "GB"]
                           (>= b 1048576.0) [(/ b 1048576.0) "MB"]
                           (>= b 1024.0) [(/ b 1024.0) "KB"]
                           :else [b "B"])]
    (if (= unit "B")
      (str (long b) " B")
      (str (String/format (Locale/forLanguageTag "pt-BR") "%.1f" (object-array [value])) " " unit))))

(defn format-duration
  "Seconds as \"2d 3h 4m\" (the largest non-zero units, at most three)."
  [seconds]
  (let [s (long seconds)
        parts (remove nil?
                      [(when (>= s 86400) (str (quot s 86400) "d"))
                       (when (>= s 3600) (str (mod (quot s 3600) 24) "h"))
                       (when (>= s 60) (str (mod (quot s 60) 60) "m"))])]
    (if (empty? parts) (str s "s") (str/join " " (take 3 parts)))))

(defn format-tps
  "TPS values as \"20.0/19.8/19.5\": one decimal, never above 20."
  [tps]
  (str/join "/" (map #(String/format Locale/ROOT "%.1f" (object-array [(double (min 20.0 (double %)))])) tps)))
