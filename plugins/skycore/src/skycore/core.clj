(ns skycore.core
  "Entry namespace of the skycore plugin. It loads every skycore namespace
  during enable (paperclj's classloader rule: no late requires) and applies
  skycore's own config."
  (:require [paperclj.core :as p]
            [skycore.cache]
            [skycore.cmd]
            [skycore.config :as cfg]
            [skycore.db]
            [skycore.events]
            [skycore.log :as log]
            [skycore.msg :as msg]
            [skycore.sched]
            [skycore.services :as services]))

(set! *warn-on-reflection* true)

(p/defplugin
  (enable [_]
    (cfg/load!)
    (reset! log/debug? (boolean (cfg/option "debug")))
    (reset! msg/language (cfg/option "language"))
    (let [n (msg/load!)]
      (log/info (str "skycore enabled (language " @msg/language ", " n " messages)"))))
  (disable [_]
    (services/release-all!)
    (log/info "skycore disabled")))
