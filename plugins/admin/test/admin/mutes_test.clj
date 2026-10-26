(ns admin.mutes-test
  (:require [admin.mutes :as mutes]
            [clojure.test :refer [deftest is testing]]))

(deftest parse-duration-test
  (is (= [:permanent] (mutes/parse-duration nil)))
  (is (= [:minutes 30] (mutes/parse-duration "30")))
  (is (= [:minutes 7] (mutes/parse-duration "007")))
  (testing "anything that is not a positive whole number of minutes is invalid"
    (doseq [s ["" "0" "00" "-5" "+5" "1.5" "1,5" "abc" "10m" " 10" "10 " "1234567890"]]
      (is (nil? (mutes/parse-duration s)) (pr-str s)))))

(deftest until-millis-test
  (is (= 1800000 (mutes/until-millis 0 [:minutes 30])))
  (is (= (+ 5000 60000) (mutes/until-millis 5000 [:minutes 1])))
  (is (nil? (mutes/until-millis 5000 [:permanent]))))

(deftest active-test
  (testing "a permanent mute never ends"
    (is (mutes/active? {:name "a" :until nil} 0))
    (is (mutes/active? {:name "a" :until nil} Long/MAX_VALUE)))
  (testing "a timed mute ends exactly at :until"
    (is (mutes/active? {:until 1000} 999))
    (is (not (mutes/active? {:until 1000} 1000)))
    (is (not (mutes/active? {:until 1000} 5000)))))

(deftest remaining-minutes-test
  (is (nil? (mutes/remaining-minutes {:until nil} 0)))
  (testing "rounded up, never zero while active"
    (is (= 30 (mutes/remaining-minutes {:until 1800000} 0)))
    (is (= 1 (mutes/remaining-minutes {:until 1800000} 1799999)))
    (is (= 2 (mutes/remaining-minutes {:until 120000} 1)))
    (is (= 1 (mutes/remaining-minutes {:until 60000} 59000)))))

(deftest db-round-trip-test
  (is (= 0 (mutes/->db-until {:until nil})))
  (is (= 1800000 (mutes/->db-until {:until 1800000})))
  (is (nil? (mutes/<-db-until 0)))
  (is (nil? (mutes/<-db-until (int 0))))
  (is (= 1800000 (mutes/<-db-until (int 1800000))))
  (testing "a stored value survives the trip"
    (doseq [u [nil 1 1800000 1761000000000]]
      (is (= u (mutes/<-db-until (mutes/->db-until {:until u})))))))
