(ns chat.death-test
  (:require [chat.death :as death]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skycore.config :as cfg])
  (:import (net.kyori.adventure.text.serializer.plain PlainTextComponentSerializer)))

(defn- plain [c] (.serialize (PlainTextComponentSerializer/plainText) c))

(def messages {"fall" ["a caiu" "b caiu" "c caiu"]
               "pvp" ["<player> perdeu para <killer>"]})

(deftest category-test
  (testing "what the damage was"
    (is (= :fall (death/category "fall" nil)))
    (is (= :lava (death/category "lava" nil)))
    (is (= :fire (death/category "on_fire" nil)))
    (is (= :fire (death/category "in_fire" nil)))
    (is (= :drown (death/category "drown" nil)))
    (is (= :void (death/category "out_of_world" nil)))
    (is (= :explosion (death/category "player_explosion" nil)))
    (is (= :cactus (death/category "cactus" nil)))
    (is (= :starve (death/category "starve" nil)))
    (is (= :magic (death/category "indirect_magic" nil)))
    (is (= :freeze (death/category "freeze" nil))))
  (testing "somebody behind the death wins over the damage"
    (is (= :pvp (death/category "player_attack" :player)))
    (is (= :pvp (death/category "fall" :player)))
    (is (= :mob (death/category "mob_attack" :mob)))
    (is (= :mob (death/category "arrow" :mob))))
  (testing "anything unknown, or missing, is generic"
    (is (= :generic (death/category "some_new_damage" nil)))
    (is (= :generic (death/category nil nil)))
    (is (= :generic (death/category "FALL?" nil)))))

(deftest choose-test
  (testing "the random number picks the variation"
    (is (= "a caiu" (death/choose (get messages "fall") (constantly 0))))
    (is (= "c caiu" (death/choose (get messages "fall") (constantly 2)))))
  (testing "the random function is asked for a number below the count"
    (let [asked (atom nil)]
      (death/choose [:x :y :z] (fn [n] (reset! asked n) 0))
      (is (= 3 @asked))))
  (testing "with real randomness every variation shows up and none is out of range"
    (let [picked (repeatedly 300 #(death/choose ["x" "y" "z"] rand-int))]
      (is (= #{"x" "y" "z"} (set picked))))))

(deftest template-test
  (is (= "b caiu" (death/template messages :fall "fallback" (constantly 1))))
  (testing "a category without variations uses the fallback"
    (is (= "fallback" (death/template messages :lava "fallback" (constantly 0))))
    (is (= "fallback" (death/template {"lava" []} :lava "fallback" (constantly 0))))
    (is (= "fallback" (death/template nil :lava "fallback" (constantly 0))))))

(deftest message-test
  (testing "player and killer are filled in"
    (is (= "Ana perdeu para Bia" (plain (death/message messages :pvp "f" "Ana" "Bia" (constantly 0))))))
  (testing "names are plain text: formatting inside a name does nothing"
    (is (= "<red>Admin perdeu para <bold>X" (plain (death/message messages :pvp "f" "<red>Admin" "<bold>X" (constantly 0))))))
  (testing "a message without a killer works when the category has no <killer>"
    (is (= "a caiu" (plain (death/message messages :fall "f" "Ana" nil (constantly 0))))))
  (testing "the fallback is rendered too"
    (is (= "Ana morreu" (plain (death/message messages :lava "<player> morreu" "Ana" nil (constantly 0)))))))

(deftest shipped-messages-test
  (let [config (cfg/parse-yaml (slurp (io/resource "config.yml")))
        messages (get-in config ["death" "messages"])]
    (testing "every category has three variations, each saying who died"
      (doseq [c death/categories]
        (let [variations (get messages (name c))]
          (is (= 3 (count variations)) (name c))
          (is (every? #(re-find #"<player>" %) variations) (name c)))))
    (testing "the ones with a killer say who it was"
      (doseq [c [:pvp :mob]]
        (is (every? #(re-find #"<killer>" %) (get messages (name c))) (name c))))
    (testing "the others do not use a killer they will not have"
      (doseq [c (remove #{:pvp :mob} death/categories)]
        (is (not-any? #(re-find #"<killer>" %) (get messages (name c))) (name c))))
    (testing "a fallback exists and every message renders"
      (is (string? (get-in config ["death" "fallback"])))
      (doseq [c death/categories
              :let [text (death/message messages c (get-in config ["death" "fallback"]) "Ana" "Bia" (constantly 0))]]
        (is (re-find #"Ana" (plain text)) (name c))))))
