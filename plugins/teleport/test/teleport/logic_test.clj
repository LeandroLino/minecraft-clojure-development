(ns teleport.logic-test
  (:require [clojure.test :refer [deftest is testing]]
            [teleport.names :as names]
            [teleport.requests :as requests]))

(deftest names-test
  (testing "valid names"
    (is (names/valid? "casa" 16))
    (is (names/valid? "Casa_2-b" 16))
    (is (names/valid? "a" 16))
    (is (names/valid? "1234567890123456" 16)))
  (testing "invalid names"
    (doseq [s [nil "" "17chars_17chars__" "com espaço" "ação" "a.b" "a/b" "../x" "a;b" 5]]
      (is (not (names/valid? s 16)) (pr-str s))))
  (testing "the limit comes from the argument"
    (is (names/valid? "abc" 3))
    (is (not (names/valid? "abcd" 3))))
  (is (= "casa" (names/normalize "CaSa"))))

(deftest can-set-home-test
  (let [homes {"a" {} "b" {}}]
    (is (= :new (names/can-set-home homes "c" 3)))
    (is (= :limit (names/can-set-home homes "c" 2)))
    (testing "overwriting an existing home never counts against the limit"
      (is (= :update (names/can-set-home homes "a" 2)))
      (is (= :update (names/can-set-home homes "a" 0))))
    (is (= :limit (names/can-set-home {} "x" 0)))
    (is (= :new (names/can-set-home {} "x" 1)))))

(deftest which-home-test
  (testing "no homes"
    (is (= [:none] (names/which-home {} nil "home")))
    (is (= [:unknown "x"] (names/which-home {} "x" "home"))))
  (testing "a typed name wins, case-insensitively"
    (is (= [:home "casa"] (names/which-home {"casa" {} "home" {}} "CASA" "home")))
    (is (= [:unknown "nope"] (names/which-home {"casa" {}} "nope" "home"))))
  (testing "no name typed"
    (is (= [:home "home"] (names/which-home {"home" {} "casa" {}} nil "home")) "the default name")
    (is (= [:home "casa"] (names/which-home {"casa" {}} nil "home")) "the only home")
    (is (= [:choose ["a" "b"]] (names/which-home {"b" {} "a" {}} nil "home")) "several, none default")))

(def ttl 60000)

(deftest requests-test
  (let [r (-> requests/empty-requests
              (requests/add :ana :bob :tpa 1000)
              (requests/add :cid :bob :tpahere 2000))]
    (testing "for-target lists newest first and only for that target"
      (is (= [:cid :ana] (map :from (requests/for-target r :bob 3000 ttl))))
      (is (empty? (requests/for-target r :ana 3000 ttl))))
    (testing "pick: the newest, or the named sender"
      (is (= :cid (:from (requests/pick r :bob nil 3000 ttl))))
      (is (= :ana (:from (requests/pick r :bob :ana 3000 ttl))))
      (is (nil? (requests/pick r :bob :zed 3000 ttl)))
      (is (= :tpahere (:type (requests/pick r :bob nil 3000 ttl)))))
    (testing "expiry is exact"
      (is (some? (requests/pick r :bob :ana (+ 1000 ttl -1) ttl)))
      (is (nil? (requests/pick r :bob :ana (+ 1000 ttl) ttl)))
      (is (= [:cid] (map :from (requests/for-target r :bob (+ 1000 ttl) ttl)))))
    (testing "prune drops the expired"
      (is (= 1 (count (requests/prune r (+ 1000 ttl) ttl))))
      (is (= 2 (count (requests/prune r 1500 ttl)))))
    (testing "a new request from the same player replaces the old one"
      (let [r2 (requests/add r :ana :bob :tpahere 5000)]
        (is (= 2 (count r2)))
        (is (= :tpahere (:type (requests/pick r2 :bob :ana 6000 ttl))))
        (is (= 5000 (:at (requests/pick r2 :bob :ana 6000 ttl))))))
    (testing "remove-request"
      (is (= 1 (count (requests/remove-request r :ana :bob))))
      (is (= 2 (count (requests/remove-request r :zed :bob)))))
    (testing "cancel-from removes everything a player sent"
      (let [r3 (requests/add r :ana :cid :tpa 2500)
            [kept n] (requests/cancel-from r3 :ana)]
        (is (= 2 n))
        (is (= 1 (count kept)))))
    (testing "forget-player removes sent and received"
      (is (empty? (requests/forget-player (requests/add requests/empty-requests :ana :bob :tpa 1) :bob)))
      (is (= 1 (count (requests/forget-player r :ana)))))))
