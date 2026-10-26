(ns paperclj.core
  "Small DSL over the Bukkit/Paper API: plugin lifecycle, commands, events,
  scheduled tasks and config.

  One Clojure runtime is shared by all plugins, so every function that needs
  'the current plugin' reads *plugin*. It is bound while a plugin enables and is
  conveyed into every handler/task the plugin registers (bound-fn*). From the
  nREPL use (with-plugin \"name\" ...). Registrations are keyed per plugin, so
  re-evaluating code replaces them instead of duplicating them."
  (:import (dev.paperclj ClojurePlugin)
           (net.kyori.adventure.text Component)
           (org.bukkit Bukkit)
           (org.bukkit.command Command CommandSender)
           (org.bukkit.configuration.file FileConfiguration)
           (org.bukkit.event EventPriority HandlerList Listener)
           (org.bukkit.plugin EventExecutor)
           (org.bukkit.scheduler BukkitScheduler BukkitTask)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- state

(def ^:dynamic *plugin* nil)

;; plugin-name -> {:ns sym, :commands {name Command}, :listeners {key Listener}, :tasks {key BukkitTask}}
(defonce ^:private registry* (atom {}))

(defn plugin
  "The plugin currently in scope (see *plugin*, with-plugin)."
  ^ClojurePlugin []
  (or *plugin*
      (throw (ex-info "No plugin in scope; call from a plugin handler or use with-plugin" {}))))

(defn- plugin-name ^String [] (.getName (plugin)))

(defn- register-in! [kind key value]
  (swap! registry* assoc-in [(plugin-name) kind key] value))

(defn- registered [kind key]
  (get-in @registry* [(plugin-name) kind key]))

(defn with-plugin*
  "Call f with *plugin* bound to the enabled plugin called `name`."
  [^String name f]
  (let [p (Bukkit/getPluginManager)]
    (binding [*plugin* (.getPlugin p name)]
      (f))))

(defmacro with-plugin
  "(with-plugin \"hello\" (config \"hello.message\")) for use from the nREPL."
  [name & body]
  `(with-plugin* ~name (fn [] ~@body)))

;; ---------------------------------------------------------------- messaging / config

(defn send-text
  "Send a plain text message to a CommandSender."
  [^CommandSender sender ^String text]
  (.sendMessage sender (Component/text text)))

(defn broadcast
  "Send a plain text message to every online player and the console."
  [^String text]
  (Bukkit/broadcast (Component/text text)))

(defn log-info [^String text]
  (.info (.getLogger (plugin)) text))

(defn config
  "Read a value from the current plugin's config.yml, falling back to default."
  ([path] (config path nil))
  ([^String path default]
   (let [^FileConfiguration cfg (.getConfig (plugin))]
     (.get cfg path default))))

(defn data-file
  "A java.io.File inside the current plugin's data folder."
  ^java.io.File [^String name]
  (java.io.File. (.getDataFolder (plugin)) name))

;; ---------------------------------------------------------------- commands

(defn- unregister-command! [name]
  (when-let [^Command old (registered :commands name)]
    (let [command-map (Bukkit/getCommandMap)
          ^java.util.Map known (.getKnownCommands command-map)]
      ;; Command.unregister only detaches the command; the map keeps every
      ;; label and alias pointing at it, so remove those entries too.
      (.unregister old command-map)
      (.removeIf (.values known) (reify java.util.function.Predicate
                                   (test [_ c] (identical? c old)))))
    (swap! registry* update-in [(plugin-name) :commands] dissoc name)))

(defn register-command!
  "Register command `name`. `handler` is (fn [sender args]) with args a vector
  of strings; return false to show the usage.

  `opts` (all optional):
    :permission   permission node; senders without it get the permission message
    :permission-message  Component sent to senders without the permission
    :aliases      seq of alternative names
    :description  help text
    :usage        usage text shown when the handler returns false
    :complete     (fn [sender args]) -> seq of tab-completion candidates"
  ([^String name handler] (register-command! name {} handler))
  ([^String name {:keys [permission permission-message aliases description usage complete]} handler]
   (unregister-command! name)
   (let [handler (bound-fn* handler)
         complete (some-> complete bound-fn*)
         ^Command cmd (proxy [Command] [name]
                        (execute [sender _label args]
                          (if (.testPermission ^Command this sender)
                            (not (false? (handler sender (vec args))))
                            true))
                        (tabComplete
                          ([sender _alias args]
                           (if (and complete (.testPermissionSilent ^Command this sender))
                             (java.util.ArrayList. ^java.util.Collection (complete sender (vec args)))
                             (java.util.ArrayList.)))
                          ([sender alias args _location]
                           (.tabComplete ^Command this sender alias args))))]
     (when permission (.setPermission cmd permission))
     (when permission-message (.permissionMessage cmd ^Component permission-message))
     (when aliases (.setAliases cmd (vec aliases)))
     (when description (.setDescription cmd description))
     (when usage (.setUsage cmd usage))
     (.register (Bukkit/getCommandMap) (plugin-name) cmd)
     (register-in! :commands name cmd)
     name)))

(defmacro defcommand
  "(defcommand hello [sender args] ...) registers /hello when evaluated, so
  place it inside the enable body (a nREPL reload re-registers it).
  An options map (see register-command!) may come before the arguments:
  (defcommand hello {:permission \"hello.use\"} [sender args] ...)"
  [cmd-name & more]
  (let [[opts [[sender args] & body]] (if (map? (first more))
                                        [(first more) (rest more)]
                                        [{} more])]
    `(register-command! ~(name cmd-name) ~opts
                        (fn [~sender ~args] ~@body))))

;; ---------------------------------------------------------------- events

(defn register-listener!
  "Listen to `event-class`. `handler` is (fn [event]). `key` identifies the
  registration so it can be replaced on reload."
  ([key ^Class event-class handler]
   (register-listener! key event-class EventPriority/NORMAL handler))
  ([key ^Class event-class ^EventPriority priority handler]
   (when-let [old (registered :listeners key)]
     (HandlerList/unregisterAll ^Listener old))
   (let [handler (bound-fn* handler)
         listener (reify Listener)
         executor (reify EventExecutor
                    ;; Bukkit shares one handler list between an event and its subclasses (every
                    ;; EntityDeathEvent reaches a PlayerDeathEvent listener), so check the class here
                    (execute [_ _ event] (when (.isInstance event-class event) (handler event))))]
     (.registerEvent (Bukkit/getPluginManager) event-class listener priority executor (plugin))
     (register-in! :listeners key listener)
     key)))

(defmacro defevent
  "(defevent on-join PlayerJoinEvent [event] ...) listens for the event. The
  registration key is the namespace-qualified handler name."
  [handler-name event-class [event] & body]
  `(register-listener! ~(keyword (str *ns*) (name handler-name))
                       ~event-class
                       (fn [~event] ~@body)))

;; ---------------------------------------------------------------- tasks

(defn- scheduler ^BukkitScheduler [] (Bukkit/getScheduler))

(defn- ->runnable ^Runnable [f]
  (let [f (bound-fn* f)]
    (reify Runnable (run [_] (f)))))

(defn run-sync
  "Run f on the main server thread on the next tick."
  ^BukkitTask [f]
  (.runTask (scheduler) (plugin) (->runnable f)))

(defn run-later
  "Run f on the main thread after `ticks` (20 ticks = 1 second)."
  ^BukkitTask [ticks f]
  (.runTaskLater (scheduler) (plugin) (->runnable f) (long ticks)))

(defn run-async
  "Run f on a worker thread. f MUST NOT touch the Bukkit API; hand results
  back to the main thread with run-sync."
  ^BukkitTask [f]
  (.runTaskAsynchronously (scheduler) (plugin) (->runnable f)))

(defn run-repeating!
  "Run f on the main thread every `period` ticks after `delay` ticks. `key`
  identifies the task so a reload replaces it."
  [key delay period f]
  (when-let [^BukkitTask old (registered :tasks key)]
    (.cancel old))
  (let [task (.runTaskTimer (scheduler) (plugin) (->runnable f) (long delay) (long period))]
    (register-in! :tasks key task)
    task))

(defmacro every
  "(every :announce {:delay 100 :period 600} body...) repeating main-thread task."
  [key {:keys [delay period]} & body]
  `(run-repeating! ~key ~delay ~period (fn [] ~@body)))

;; ---------------------------------------------------------------- lifecycle

(defmacro defplugin
  "Defines the plugin lifecycle in the current namespace:
  (defplugin
    (enable [plugin] ...)
    (disable [plugin] ...))"
  [& clauses]
  (let [by-name (into {} (map (juxt (comp name first) rest)) clauses)
        fn-form (fn [clause-name]
                  (let [[args & body] (get by-name clause-name)]
                    `(fn ~(or args '[_]) ~@body)))]
    `(do
       (def ~'on-enable ~(fn-form "enable"))
       (def ~'on-disable ~(fn-form "disable")))))

(defn- lifecycle-fn [var-sym]
  (let [ns-sym (get-in @registry* [(plugin-name) :ns])]
    @(ns-resolve (the-ns ns-sym) var-sym)))

(defn enable!
  "Called by ClojurePlugin: load the entry namespace and run its on-enable."
  [^ClojurePlugin p ^String ns-name]
  (binding [*plugin* p]
    (swap! registry* assoc (plugin-name) {:ns (symbol ns-name)})
    (.saveDefaultConfig p)
    (require (symbol ns-name))
    ((lifecycle-fn 'on-enable) p)))

(defn disable!
  "Called by ClojurePlugin: run on-disable and undo every registration."
  [^ClojurePlugin p]
  (binding [*plugin* p]
    ((lifecycle-fn 'on-disable) p)
    (.cancelTasks (scheduler) p)
    (doseq [[_ l] (:listeners (get @registry* (plugin-name)))]
      (HandlerList/unregisterAll ^Listener l))
    (doseq [name (keys (:commands (get @registry* (plugin-name))))]
      (unregister-command! name))
    (swap! registry* dissoc (plugin-name))))

(defn shutdown!
  "Called by the paperclj plugin on disable: stop the nREPL if it was started."
  []
  (when-let [stop! (some-> (find-ns 'paperclj.nrepl) (ns-resolve 'stop!))]
    (stop!)))
