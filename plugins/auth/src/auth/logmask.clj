(ns auth.logmask
  "Keeps passwords out of the server log. The game writes every command a player runs to the
  console and to logs/latest.log (\"Ana issued server command: /login hunter2\"), so a filter on
  the Log4j2 configuration swallows such a line and writes it again with the password replaced by
  ***. It is a best effort on a logging system that is not ours: the filter is built with
  reflection and a dynamic proxy, and if anything about it fails the plugin only warns, and the
  passwords stay in the log (the README says so).

  `mask` is pure and tested; `install!` and `uninstall!` are the Log4j2 part."
  (:require [clojure.string :as str]
            [skycore.log :as log])
  (:import (java.lang.reflect InvocationHandler Method Proxy)
           (java.util.regex Pattern)
           (org.bukkit Bukkit)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- the pure part

(def ^:private secret-commands
  "Command lines whose arguments are passwords, each with the group that must stay readable."
  [(Pattern/compile "(?i)(issued server command: /(?:auth:)?(?:login|register)\\s+).+")
   (Pattern/compile "(?i)(issued server command: /(?:auth:)?sky\\s+senha\\s+\\S+\\s+).+")])

(defn mask
  "`text` with the arguments of /login, /register and /sky senha <player> replaced by ***. A line
  that is not such a command, or is masked already, comes back unchanged."
  ^String [^String text]
  (if (and text (str/includes? text "issued server command"))
    (reduce (fn [^String t ^Pattern p] (.replaceAll (.matcher p t) "$1***")) text secret-commands)
    text))

(defn format-params
  "`pattern` with each {} filled, in order, from `params` (a Log4j2 style message)."
  ^String [^String pattern params]
  (loop [parts (str/split (str pattern) #"\{\}" -1)
         params (seq params)
         out (StringBuilder.)]
    (cond
      (empty? parts) (str out)
      (empty? (rest parts)) (str (.append out ^String (first parts)))
      :else (recur (rest parts) (next params)
                   (-> out (.append ^String (first parts)) (.append (if params (str (first params)) "{}")))))))

;; ---------------------------------------------------------------- Log4j2, by reflection

(defonce ^:private installed (atom nil)) ; {:filter proxy :remove (fn [])}

(defn- clazz ^Class [^ClassLoader cl ^String name] (Class/forName name true cl))

(defn- call [target ^String method-name]
  (let [^Method m (.getMethod (.getClass ^Object target) method-name (make-array Class 0))]
    (.setAccessible m true)
    (.invoke m target (object-array 0))))

(defn- enum-value [^Class enum-class ^String value-name]
  (first (filter #(= value-name (.name ^Enum %)) (.getEnumConstants enum-class))))

(defn- message-text
  "The text a `filter` call is about, whichever overload it is: a LogEvent, a Message, or a
  pattern with its parameters. nil when it cannot tell."
  [log-event-class message-class args]
  (loop [as (seq args)]
    (when as
      (let [a (first as)]
        (cond
          (nil? a) (recur (next as))
          (.isInstance ^Class log-event-class a) (str (call (call a "getMessage") "getFormattedMessage"))
          (.isInstance ^Class message-class a) (str (call a "getFormattedMessage"))
          (instance? CharSequence a)
          (format-params (str a) (mapcat #(if (and % (.isArray (.getClass ^Object %))) (seq %) [%]) (next as)))
          :else (recur (next as)))))))

(defn- logger-and-level
  "The Log4j2 logger and level to write the masked line with, from the arguments of `filter`."
  [^ClassLoader cl log-event-class args]
  (let [event (first (filter #(and % (.isInstance ^Class log-event-class %)) args))
        log-manager (clazz cl "org.apache.logging.log4j.LogManager")
        level-class (clazz cl "org.apache.logging.log4j.Level")]
    (if event
      [(.invoke (.getMethod log-manager "getLogger" (into-array Class [String])) nil
                (object-array [(or (call event "getLoggerName") "Minecraft")]))
       (call event "getLevel")]
      [(first args) (first (filter #(and % (.isInstance level-class %)) args))])))

(defn install!
  "Put the filter on the Log4j2 configuration. Returns true, or false (after a warning) when it could
  not. Safe to call twice."
  []
  (if @installed
    true
    (try
      (let [cl (.getClassLoader ^Class Bukkit)
            filter-class (clazz cl "org.apache.logging.log4j.core.Filter")
            result-class (clazz cl "org.apache.logging.log4j.core.Filter$Result")
            state-class (clazz cl "org.apache.logging.log4j.core.LifeCycle$State")
            log-event-class (clazz cl "org.apache.logging.log4j.core.LogEvent")
            message-class (clazz cl "org.apache.logging.log4j.message.Message")
            logger-class (clazz cl "org.apache.logging.log4j.Logger")
            level-class (clazz cl "org.apache.logging.log4j.Level")
            neutral (enum-value result-class "NEUTRAL")
            deny (enum-value result-class "DENY")
            started (enum-value state-class "STARTED")
            write-masked! (fn [args masked]
                            (let [[logger level] (logger-and-level cl log-event-class args)]
                              (when (and logger level)
                                (.invoke (.getMethod logger-class "log" (into-array Class [level-class String]))
                                         logger (object-array [level masked])))))
            handler (reify InvocationHandler
                      (invoke [this proxy method args]
                        (let [name (.getName ^Method method)]
                          (case name
                            "filter"
                            (try
                              (let [text (message-text log-event-class message-class args)]
                                (if (and text (not= text (mask text)))
                                  (do (write-masked! args (mask text)) deny)
                                  neutral))
                              (catch Throwable _ neutral))
                            ("getOnMatch" "getOnMismatch") neutral
                            "isStarted" true
                            "isStopped" false
                            "getState" started
                            "stop" (if (and args (pos? (alength ^objects args))) true nil)
                            ("start" "initialize") nil
                            "hashCode" (System/identityHashCode proxy)
                            "equals" (identical? proxy (aget ^objects args 0))
                            "toString" "auth-password-mask"
                            nil))))
            filter (Proxy/newProxyInstance cl (into-array Class [filter-class]) handler)
            log-manager (clazz cl "org.apache.logging.log4j.LogManager")
            ctx (.invoke (.getMethod log-manager "getContext" (into-array Class [Boolean/TYPE])) nil (object-array [false]))
            config (call ctx "getConfiguration")
            filterable (clazz cl "org.apache.logging.log4j.core.filter.Filterable")
            add (.getMethod filterable "addFilter" (into-array Class [filter-class]))
            remove-method (.getMethod filterable "removeFilter" (into-array Class [filter-class]))]
        (.invoke add config (object-array [filter]))
        (reset! installed {:remove (fn [] (.invoke remove-method config (object-array [filter])))})
        true)
      (catch Throwable t
        (log/warn (str "could not install the log filter that hides passwords (" (.getMessage t)
                       "); /login and /register passwords will show in the server log"))
        false))))

(defn uninstall!
  "Take the filter off again (the plugin is stopping)."
  []
  (when-let [{:keys [remove]} @installed]
    (try (remove) (catch Throwable _ nil))
    (reset! installed nil)))
