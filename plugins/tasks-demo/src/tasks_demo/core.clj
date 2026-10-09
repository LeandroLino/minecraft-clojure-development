(ns tasks-demo.core
  (:require [paperclj.core :as p]))

(set! *warn-on-reflection* true)

(def ^:private ticks-per-second 20)

(defn seconds->ticks [seconds]
  (* ticks-per-second (long seconds)))

(defn count-primes
  "Deliberately CPU-bound work, to run off the main thread."
  [limit]
  (let [limit (int limit)
        composite (boolean-array (inc limit))]
    (loop [i 2 found 0]
      (if (> i limit)
        found
        (if (aget composite i)
          (recur (inc i) found)
          (do (loop [j (* i i)]
                (when (<= j limit)
                  (aset composite j true)
                  (recur (+ j i))))
              (recur (inc i) (inc found)))))))) 

(defn start-announcer!
  "Main-thread repeating task: broadcast the configured message."
  []
  (p/every ::announce {:delay (seconds->ticks (p/config "tasks-demo.announce-delay-seconds" 10))
                       :period (seconds->ticks (p/config "tasks-demo.announce-interval-seconds" 60))}
    (p/broadcast (p/config "tasks-demo.announce-message" "[tasks-demo] ping"))))

(defn start-async-demo!
  "Compute on a worker thread (no Bukkit API there), then hand the result to
  the main thread with run-sync, where it is safe to touch the server."
  []
  (p/run-async
   (fn []
     (let [limit 5000000
           n (count-primes limit)]
       (p/run-sync
        (fn [] (p/log-info (str "async result: " n " primes below " limit))))))))

(p/defplugin
  (enable [_]
    (start-announcer!)
    (start-async-demo!)
    (p/log-info "tasks-demo enabled"))
  (disable [_]
    (p/log-info "tasks-demo disabled")))
