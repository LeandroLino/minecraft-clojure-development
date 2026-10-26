(ns admin.archive
  "Writing a backup zip. Plain java.io / java.nio, no Bukkit, so it can be
  tested against a temporary folder. Meant to run off the main thread."
  (:require [clojure.string :as str])
  (:import (java.io BufferedOutputStream File FileInputStream FileOutputStream IOException)
           (java.nio.file Files Path StandardCopyOption)
           (java.util.zip ZipEntry ZipOutputStream)))

(set! *warn-on-reflection* true)

(defn- entry-name
  "prefix + the path of `file` under `root`, with / separators."
  ^String [^String prefix ^File root ^File file]
  (str prefix (str/replace (str (.relativize (.toPath root) (.toPath file))) File/separator "/")))

(defn- copy-file!
  "Add one file to the zip. Returns :ok, or :skipped when it cannot be read
  (a file the server deleted or locked in the meantime)."
  [^ZipOutputStream zip ^String name ^File file]
  (try
    (with-open [in (FileInputStream. file)]
      (.putNextEntry zip (doto (ZipEntry. name) (.setTime (.lastModified file))))
      (.transferTo in zip)
      (.closeEntry zip)
      :ok)
    (catch IOException _
      ;; the entry may be half written: close it so the zip stays valid
      (try (.closeEntry zip) (catch IOException _ nil))
      :skipped)))

(defn add-tree!
  "Add every file under `root` to the zip as prefix/relative/path, except those
  for which (skip? relative-path) is true. Returns {:files n :skipped n}."
  [^ZipOutputStream zip ^File root ^String prefix skip?]
  (if-not (.isDirectory root)
    {:files 0 :skipped 0}
    (let [paths (with-open [stream (Files/walk (.toPath root) (make-array java.nio.file.FileVisitOption 0))]
                  (vec (iterator-seq (.iterator stream))))]
      (reduce (fn [acc ^Path path]
                (let [file (.toFile path)
                      name (entry-name prefix root file)]
                  (cond
                    (not (.isFile file)) acc
                    (skip? (subs name (count prefix))) acc
                    :else (case (copy-file! zip name file)
                            :ok (update acc :files inc)
                            :skipped (update acc :skipped inc)))))
              {:files 0 :skipped 0}
              paths))))

(defn write-zip!
  "Write a zip at `target` from `sources`, a seq of {:root File :prefix String
  :skip? fn}. The zip is built as <target>.tmp and renamed when complete, so a
  crash never leaves a half-written file with the final name.
  Returns {:files n :skipped n :bytes n}."
  [^File target sources]
  (let [tmp (File. (str (.getPath target) ".tmp"))]
    (try
      (let [totals (with-open [zip (ZipOutputStream. (BufferedOutputStream. (FileOutputStream. tmp)))]
                     (reduce (fn [acc {:keys [root prefix skip?]}]
                               (merge-with + acc (add-tree! zip root prefix (or skip? (constantly false)))))
                             {:files 0 :skipped 0}
                             sources))]
        (Files/move (.toPath tmp) (.toPath target) (into-array java.nio.file.CopyOption [StandardCopyOption/REPLACE_EXISTING]))
        (assoc totals :bytes (.length target)))
      (catch Throwable t
        (Files/deleteIfExists (.toPath tmp))
        (throw t)))))

(defn outermost
  "Of these folders, those not inside another one, without duplicates. Paper
  can keep the nether and the end inside the main world's folder, and the
  backup must not store them twice."
  [folders]
  (let [paths (vec (distinct (map (fn [^File f] (.toPath (.getCanonicalFile f))) folders)))]
    (vec (for [^Path path paths
               :when (not-any? (fn [^Path other] (and (not= path other) (.startsWith path other))) paths)]
           (.toFile path)))))

(defn level-root
  "The folder that holds a world's level.dat, player data and global data.
  Paper 26 keeps each dimension in <level>/dimensions/minecraft/<dimension>, so
  from a dimension's folder this climbs to <level>; older layouts have
  level.dat in the folder itself. `stop-at` (the server folder) is never
  crossed. If no level.dat is found, `folder` itself is returned."
  ^File [^File folder ^File stop-at]
  (let [stop (.getCanonicalFile stop-at)]
    (loop [dir (.getCanonicalFile folder)]
      (cond
        (.isFile (File. dir "level.dat")) dir
        (or (nil? (.getParentFile dir)) (= dir stop)) (.getCanonicalFile folder)
        :else (recur (.getParentFile dir))))))
