(ns skycore.events
  "Event listeners with the options Bukkit has and the paperclj DSL does not
  expose (priority by keyword, ignore-cancelled), and a handler that logs its
  own exceptions instead of leaking them to the event bus."
  (:require [clojure.string :as str]
            [paperclj.core :as p]
            [skycore.log :as log])
  (:import (org.bukkit.event Cancellable EventPriority)))

(set! *warn-on-reflection* true)

(defn listen!
  "Listen to `event-class`. `key` identifies the registration (a reload
  replaces it). Options: :priority (:lowest :low :normal :high :highest
  :monitor, default :normal) and :ignore-cancelled (default false)."
  [key ^Class event-class {:keys [priority ignore-cancelled]
                           :or {priority :normal ignore-cancelled false}} handler]
  (p/register-listener!
   key event-class
   (EventPriority/valueOf (str/upper-case (name priority)))
   (fn [event]
     (when-not (and ignore-cancelled
                    (instance? Cancellable event)
                    (.isCancelled ^Cancellable event))
       (try
         (handler event)
         (catch Throwable t
           (log/error t (str "event handler " key " failed"))))))))

(defmacro defhandler
  "(defhandler on-break BlockBreakEvent {:priority :high :ignore-cancelled true} [event] ...)
  Place it in the enable body. The options map may be omitted."
  [handler-name event-class & more]
  (let [[opts [[event] & body]] (if (map? (first more))
                                  [(first more) (rest more)]
                                  [{} more])]
    `(listen! ~(keyword (str *ns*) (name handler-name))
              ~event-class
              ~opts
              (fn [~event] ~@body))))
