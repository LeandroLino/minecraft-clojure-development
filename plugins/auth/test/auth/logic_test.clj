(ns auth.logic-test
  (:require [auth.logic :as logic]
            [clojure.test :refer [deftest is testing]]))

(deftest password-error-test
  (is (nil? (logic/password-error "123456" 6 64)))
  (is (= :too-short (logic/password-error "12345" 6 64)))
  (is (= :too-short (logic/password-error "" 6 64)))
  (is (nil? (logic/password-error (apply str (repeat 64 "a")) 6 64)))
  (is (= :too-long (logic/password-error (apply str (repeat 65 "a")) 6 64))))

(deftest command-label-test
  (is (= "login" (logic/command-label "/login abc")))
  (is (= "login" (logic/command-label "/LOGIN abc")))
  (is (= "login" (logic/command-label "  /login")))
  (is (= "auth:login" (logic/command-label "/auth:login abc")))
  (is (= "" (logic/command-label "")))
  (is (= "" (logic/command-label nil)))
  (is (= "msg" (logic/command-label "/msg ana oi"))))

(deftest allowed-command-test
  (testing "only /login and /register, with or without the auth: prefix"
    (is (logic/allowed-command? "/login segredo"))
    (is (logic/allowed-command? "/register a b"))
    (is (logic/allowed-command? "/Login segredo"))
    (is (logic/allowed-command? "/auth:login segredo"))
    (is (logic/allowed-command? "/auth:register a b")))
  (testing "everything else is refused, play and movement commands included"
    (doseq [c ["/play" "/jogar" "/home" "/warp spawn" "/tpa ana" "/msg ana oi" "/sky status"
               "/minecraft:me hi" "/bukkit:help" "/auth:other" "/loginx abc" "/log in" "" "oi" "/"]]
      (is (not (logic/allowed-command? c)) c))))

(deftest wrong-password-test
  (is (= {:attempts 1 :left 2 :kick? false} (logic/wrong-password 0 3)))
  (is (= {:attempts 2 :left 1 :kick? false} (logic/wrong-password 1 3)))
  (testing "the third wrong password kicks"
    (is (= {:attempts 3 :left 0 :kick? true} (logic/wrong-password 2 3))))
  (testing "a limit of one kicks at once"
    (is (:kick? (logic/wrong-password 0 1)))))

(deftest time-limit-test
  (is (= 60 (logic/seconds-left 1000 1000 60)))
  (is (= 30 (logic/seconds-left 0 30000 60)))
  (is (= 1 (logic/seconds-left 0 59999 60)))
  (is (= 0 (logic/seconds-left 0 60000 60)))
  (is (= 0 (logic/seconds-left 0 99999 60)))
  (is (not (logic/timed-out? 0 59999 60)))
  (is (logic/timed-out? 0 60000 60))
  (is (logic/timed-out? 1000 61000 60)))

(deftest remembered-address-test
  (let [t0 1000000
        five-min (* 5 60 1000)
        r (logic/remember {} :ana "10.0.0.1" t0 5)]
    (testing "the same player from the same address, in time"
      (is (logic/remembered? r :ana "10.0.0.1" t0))
      (is (logic/remembered? r :ana "10.0.0.1" (+ t0 five-min -1))))
    (testing "not after five minutes"
      (is (not (logic/remembered? r :ana "10.0.0.1" (+ t0 five-min)))))
    (testing "not from another address, not for another player, not for nobody"
      (is (not (logic/remembered? r :ana "10.0.0.2" t0)))
      (is (not (logic/remembered? r :bia "10.0.0.1" t0)))
      (is (not (logic/remembered? r :ana nil t0)))
      (is (not (logic/remembered? {} :ana "10.0.0.1" t0))))
    (testing "remembering again moves the deadline"
      (let [later (logic/remember r :ana "10.0.0.1" (+ t0 1000) 5)]
        (is (logic/remembered? later :ana "10.0.0.1" (+ t0 five-min)))))
    (testing "prune drops what ran out and keeps the rest"
      (let [two (-> r (logic/remember :bia "10.0.0.9" (+ t0 five-min) 5))
            pruned (logic/prune two (+ t0 five-min))]
        (is (= #{:bia} (set (keys pruned))))))))
