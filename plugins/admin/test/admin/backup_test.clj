(ns admin.backup-test
  (:require [admin.archive :as archive]
            [admin.retention :as retention]
            [clojure.test :refer [deftest is testing]])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant ZoneId)
           (java.util.zip ZipFile)))

(deftest backup-name-test
  (let [at (Instant/parse "2026-10-22T12:34:56Z")]
    (is (= "backup-20261022-123456.zip" (retention/backup-name at (ZoneId/of "UTC"))))
    (testing "the zone shifts the stamp"
      (is (= "backup-20261022-093456.zip" (retention/backup-name at (ZoneId/of "America/Sao_Paulo")))))
    (testing "names sort like time"
      (is (neg? (compare (retention/backup-name (Instant/parse "2026-10-22T12:34:56Z") (ZoneId/of "UTC"))
                         (retention/backup-name (Instant/parse "2026-10-22T12:34:57Z") (ZoneId/of "UTC"))))))))

(deftest backup-file-test
  (is (retention/backup-file? "backup-20261022-123456.zip"))
  (doseq [n ["backup-20261022-123456.zip.tmp" "notes.txt" "backup-2026.zip" "xbackup-20261022-123456.zip"
             "backup-20261022-123456.zip " nil ""]]
    (is (not (retention/backup-file? n)) (pr-str n))))

(deftest to-delete-test
  (let [names ["backup-20261022-100000.zip" "backup-20261022-090000.zip" "backup-20261022-110000.zip"
               "backup-20261021-230000.zip" "readme.txt" "my-save.zip"]]
    (testing "keeps the newest N, returns the older ones oldest first"
      (is (= ["backup-20261021-230000.zip" "backup-20261022-090000.zip"] (retention/to-delete names 2))))
    (testing "keep >= count deletes nothing"
      (is (empty? (retention/to-delete names 4)))
      (is (empty? (retention/to-delete names 99))))
    (testing "files that are not backups are never listed, whatever keep is"
      (is (every? retention/backup-file? (retention/to-delete names 0)))
      (is (= 4 (count (retention/to-delete names 0)))))
    (testing "a negative keep is treated as zero, not as 'delete more'"
      (is (= (retention/to-delete names 0) (retention/to-delete names -5))))
    (is (empty? (retention/to-delete [] 3)))))

(deftest format-test
  (is (= "512 B" (retention/format-size 512)))
  (is (= "1,5 KB" (retention/format-size 1536)))
  (is (= "2,0 MB" (retention/format-size (* 2 1048576))))
  (is (= "1,5 GB" (retention/format-size (* 1.5 1073741824))))
  (is (= "45s" (retention/format-duration 45)))
  (is (= "1m" (retention/format-duration 60)))
  (is (= "2h 5m" (retention/format-duration (+ (* 2 3600) 300))))
  (is (= "3d 4h 5m" (retention/format-duration (+ (* 3 86400) (* 4 3600) 300)))))

(defn- temp-dir ^File [] (.toFile (Files/createTempDirectory "backup-test" (make-array FileAttribute 0))))
(defn- spit-file [^File dir ^String rel ^String text]
  (let [f (File. dir rel)] (.mkdirs (.getParentFile f)) (spit f text) f))
(defn- delete-tree! [^File dir]
  (doseq [f (reverse (file-seq dir))] (.delete ^File f)))

(deftest write-zip-test
  (let [tmp (temp-dir)
        world (File. tmp "world")
        plugins (File. tmp "plugins")
        out (File. tmp "out.zip")]
    (try
      (spit-file world "level.dat" "level")
      (spit-file world "region/r.0.0.mca" "regiondata")
      (spit-file world "session.lock" "lock")
      (spit-file plugins "economy/config.yml" "cfg")
      (spit-file plugins "economy/economy.db" "LIVE-DB")
      (spit-file plugins "economy/economy.db-wal" "WAL")
      (let [result (archive/write-zip!
                    out
                    [{:root world :prefix "worlds/world/" :skip? #(= "session.lock" %)}
                     {:root plugins :prefix "plugins/" :skip? #(re-find #"\.db(-wal|-shm)?$" %)}])]
        (testing "the report"
          (is (= 3 (:files result)))
          (is (= 0 (:skipped result)))
          (is (= (.length out) (:bytes result))))
        (testing "the zip has the wanted files, with / paths, and nothing else"
          (with-open [zf (ZipFile. out)]
            (is (= #{"worlds/world/level.dat" "worlds/world/region/r.0.0.mca" "plugins/economy/config.yml"}
                   (set (map #(.getName ^java.util.zip.ZipEntry %) (enumeration-seq (.entries zf))))))
            (is (= "regiondata" (slurp (.getInputStream zf (.getEntry zf "worlds/world/region/r.0.0.mca")))))))
        (testing "no temporary file is left behind"
          (is (not (.exists (File. tmp "out.zip.tmp"))))))
      (finally (delete-tree! tmp)))))

(deftest write-zip-extra-sources-test
  (testing "a source that does not exist adds nothing; a replacement snapshot lands under the wanted name"
    (let [tmp (temp-dir)
          snap (File. tmp "snap")
          out (File. tmp "out.zip")]
      (try
        (spit-file snap "economy/economy.db" "SNAPSHOT")
        (let [result (archive/write-zip! out [{:root (File. tmp "missing") :prefix "worlds/x/"}
                                              {:root snap :prefix "plugins/"}])]
          (is (= 1 (:files result)))
          (with-open [zf (ZipFile. out)]
            (is (= "SNAPSHOT" (slurp (.getInputStream zf (.getEntry zf "plugins/economy/economy.db")))))))
        (finally (delete-tree! tmp))))))

(deftest write-zip-failure-leaves-no-file-test
  (testing "when writing fails, neither the final file nor the .tmp remain"
    (let [tmp (temp-dir)
          out (File. tmp "no-such-dir/out.zip")]
      (try
        (is (thrown? Exception (archive/write-zip! out [{:root tmp :prefix "x/"}])))
        (is (not (.exists out)))
        (is (not (.exists (File. tmp "no-such-dir/out.zip.tmp"))))
        (finally (delete-tree! tmp))))))

(deftest format-tps-test
  (is (= "20.0/19.8/19.5" (retention/format-tps [20.0 19.84 19.5])))
  (is (= "20.0/20.0" (retention/format-tps [21.3 20.0])) "capped at 20")
  (is (= "" (retention/format-tps []))))

(deftest outermost-test
  (let [tmp (temp-dir)
        world (File. tmp "world")
        nether (File. world "DIM-1")
        other (File. tmp "other")]
    (try
      (doseq [d [world nether other]] (.mkdirs ^File d))
      (testing "a folder inside another one is dropped"
        (is (= #{(.getCanonicalFile world) (.getCanonicalFile other)}
               (set (archive/outermost [world nether other])))))
      (testing "duplicates are dropped, order does not matter"
        (is (= [(.getCanonicalFile world)] (archive/outermost [nether world world nether]))))
      (testing "a folder whose name merely starts like another one is kept"
        (let [sibling (File. tmp "world-old")]
          (.mkdirs sibling)
          (is (= 2 (count (archive/outermost [world sibling]))))))
      (testing "nothing in, nothing out"
        (is (empty? (archive/outermost []))))
      (finally (delete-tree! tmp)))))

(deftest level-root-test
  (let [tmp (temp-dir)
        level (File. tmp "world")
        overworld (File. level "dimensions/minecraft/overworld")
        nether (File. level "dimensions/minecraft/the_nether")
        legacy (File. tmp "oldworld")]
    (try
      (doseq [d [overworld nether legacy]] (.mkdirs ^File d))
      (spit (File. level "level.dat") "x")
      (spit (File. legacy "level.dat") "x")
      (testing "a dimension folder resolves to the level folder that has level.dat"
        (is (= (.getCanonicalFile level) (archive/level-root overworld tmp)))
        (is (= (.getCanonicalFile level) (archive/level-root nether tmp))))
      (testing "the older layout: level.dat in the folder itself"
        (is (= (.getCanonicalFile legacy) (archive/level-root legacy tmp))))
      (testing "all dimensions of one level collapse to one folder"
        (is (= [(.getCanonicalFile level)]
               (archive/outermost (map #(archive/level-root % tmp) [overworld nether])))))
      (testing "without a level.dat anywhere the folder itself is kept, and the server folder is never crossed"
        (let [bare (File. tmp "bare/dim")]
          (.mkdirs bare)
          (is (= (.getCanonicalFile bare) (archive/level-root bare tmp)))))
      (finally (delete-tree! tmp)))))
