(ns hello.core
  (:require [clojure.string :as str]
            [paperclj.core :as p])
  (:import (org.bukkit.command CommandSender)))

(set! *warn-on-reflection* true)

(defn render-message
  "Fill the {player} placeholder of the configured template."
  [template player]
  (str/replace template "{player}" player))

(defn target-name
  "Name given as arguments, or the sender's own name."
  [^CommandSender sender args]
  (if (seq args)
    (str/join " " args)
    (.getName sender)))

(defn hello-command [sender args]
  (let [template (p/config "hello.message" "Hello, {player}!")]
    (p/send-text sender (render-message template (target-name sender args)))
    true))

(p/defplugin
  (enable [_]
    (p/defcommand hello [sender args] (hello-command sender args))
    (p/log-info "hello enabled"))
  (disable [_]
    (p/log-info "hello disabled")))
