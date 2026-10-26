(ns skycore.db
  "SQLite for the modules, never on the main thread.

  Each database has ONE worker thread, so statements run in submission order
  and SQLite never sees concurrent writers. `open!` returns at once (the file is
  opened on the worker); every operation is asynchronous and delivers its result
  to a callback ON THE MAIN THREAD, where it is safe to touch the world.

  The JDBC driver is the one Paper already ships (server/libraries), so there is
  no extra dependency. `close!` is the one blocking call: it waits (bounded by
  db.close-timeout-seconds) for pending writes so nothing is lost on disable."
  (:require [paperclj.core :as p]
            [skycore.config :as cfg]
            [skycore.log :as log])
  (:import (java.io File)
           (java.lang.reflect Constructor)
           (java.sql Connection Driver PreparedStatement ResultSet ResultSetMetaData)
           (java.util Properties)
           (java.util.concurrent Callable ExecutorService Executors Future ThreadFactory TimeUnit)
           (org.bukkit Bukkit)))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------- connection

(defn- connect
  "Open `file` (created if missing). Called on the db worker thread only."
  ^Connection [^File file busy-timeout-ms]
  (.mkdirs (.getParentFile file))
  (let [^Class bukkit-class Bukkit
        ^Class driver-class (Class/forName "org.sqlite.JDBC" true (.getClassLoader bukkit-class))
        ^Constructor ctor (.getDeclaredConstructor driver-class ^"[Ljava.lang.Class;" (make-array Class 0))
        ^Driver driver (.newInstance ctor (object-array 0))
        conn (.connect driver (str "jdbc:sqlite:" (.getAbsolutePath file)) (Properties.))]
    (with-open [st (.createStatement conn)]
      (.execute st "PRAGMA journal_mode=WAL")
      (.execute st "PRAGMA foreign_keys=ON")
      (.execute st (str "PRAGMA busy_timeout=" (long busy-timeout-ms))))
    conn))

(defn open!
  "Open the SQLite database at `file` for the current plugin. Returns a db
  handle immediately."
  [^File file]
  (let [plugin-name (.getName (p/plugin))
        busy-timeout (cfg/option-of "skycore" "db.busy-timeout-ms")
        close-timeout (cfg/option-of "skycore" "db.close-timeout-seconds")
        exec (Executors/newSingleThreadExecutor
              (reify ThreadFactory
                (newThread [_ r] (doto (Thread. r (str "skycore-db-" plugin-name))
                                   (.setDaemon true)))))
        conn (.submit exec ^Callable (fn [] (connect file busy-timeout)))]
    {:exec exec :conn conn :closing (atom false) :close-timeout-seconds close-timeout}))

;; ---------------------------------------------------------------- statements (db thread only)

(defn- bind! [^PreparedStatement ps params]
  (doseq [[i v] (map-indexed vector params)]
    (.setObject ps (int (inc i)) v)))

(defn- row->map [^ResultSet rs ^ResultSetMetaData md]
  (into {}
        (map (fn [i]
               [(keyword (.toLowerCase (.getColumnLabel md (int i))))
                (.getObject rs (int i))]))
        (range 1 (inc (.getColumnCount md)))))

(defn exec-sql
  "Run an INSERT/UPDATE/DELETE/DDL on `conn`; returns the update count.
  For use inside `task!`/`transact!` functions, i.e. on the db thread."
  [^Connection conn ^String sql params]
  (with-open [ps (.prepareStatement conn sql)]
    (bind! ps params)
    (.executeUpdate ps)))

(defn query-sql
  "Run a SELECT on `conn`; returns a vector of maps (lower-case keyword keys).
  For use inside `task!`/`transact!` functions, i.e. on the db thread."
  [^Connection conn ^String sql params]
  (with-open [ps (.prepareStatement conn sql)]
    (bind! ps params)
    (with-open [rs (.executeQuery ps)]
      (let [md (.getMetaData rs)]
        (loop [rows (transient [])]
          (if (.next rs)
            (recur (conj! rows (row->map rs md)))
            (persistent! rows)))))))

;; ---------------------------------------------------------------- async API

(defn task!
  "Run (f conn) on the db thread; give its result to `callback` on the main
  thread (callback may be nil). Failures are logged and skip the callback.
  Returns the Future."
  ^Future [db f callback]
  (let [{:keys [^ExecutorService exec ^Future conn closing]} db
        task (bound-fn*
              (fn []
                (try
                  (let [result (f (.get conn))]
                    (when (and callback (not @closing))
                      (p/run-sync (fn [] (callback result))))
                    result)
                  (catch Throwable t
                    (log/error t "database task failed")
                    nil))))]
    (.submit exec ^Callable task)))

(defn transact!
  "Like task!, but (f conn) runs in one transaction: committed if it returns,
  rolled back if it throws."
  ^Future [db f callback]
  (task! db
        (fn [^Connection conn]
          (.setAutoCommit conn false)
          (try
            (let [result (f conn)]
              (.commit conn)
              result)
            (catch Throwable t
              (.rollback conn)
              (throw t))
            (finally
              (.setAutoCommit conn true))))
        callback))

(defn execute!
  "Run an update statement; callback receives the update count."
  ^Future [db sql params callback]
  (task! db #(exec-sql % sql params) callback))

(defn query!
  "Run a SELECT; callback receives a vector of row maps."
  ^Future [db sql params callback]
  (task! db #(query-sql % sql params) callback))

(defn close!
  "Flush and close. BLOCKS the calling thread until pending tasks finish or
  db.close-timeout-seconds passes: call it from a module's `disable`."
  [db]
  (let [{:keys [^ExecutorService exec ^Future conn closing close-timeout-seconds]} db]
    (reset! closing true)
    (.submit exec ^Callable (fn []
                              (try
                                (.close ^Connection (.get conn))
                                (catch Throwable t
                                  (log/error t "closing database failed")))))
    (.shutdown exec)
    (when-not (.awaitTermination exec (long close-timeout-seconds) TimeUnit/SECONDS)
      (log/warn "database did not flush in time; forcing shutdown")
      (.shutdownNow exec))))
