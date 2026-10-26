(ns skycore.sched
  "Scheduler helpers on top of paperclj. The rule they enforce: blocking work
  (disk, database, network) runs off the main thread, and anything that
  touches the world, entities or players runs on it."
  (:require [paperclj.core :as p]
            [skycore.log :as log])
  (:import (org.bukkit Bukkit)))

(set! *warn-on-reflection* true)

(defn main-thread?
  "True on the server's main thread."
  []
  (Bukkit/isPrimaryThread))

(defn safe
  "Wrap f so an exception is logged instead of escaping into the scheduler."
  [what f]
  (fn [& args]
    (try
      (apply f args)
      (catch Throwable t
        (log/error t (str what " failed"))
        nil))))

(defn async-then
  "Run `work` on a worker thread (no Bukkit API there) and pass its result to
  `on-done` on the main thread. If `work` throws, the error is logged and
  `on-error` (optional) is called on the main thread with the exception."
  ([work on-done] (async-then work on-done nil))
  ([work on-done on-error]
   (p/run-async
    (fn []
      (try
        (let [result (work)]
          (p/run-sync (safe "async-then callback" #(on-done result))))
        (catch Throwable t
          (log/error t "async task failed")
          (when on-error
            (p/run-sync (safe "async-then error callback" #(on-error t))))))))))
