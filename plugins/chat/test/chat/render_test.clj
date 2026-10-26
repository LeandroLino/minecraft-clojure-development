(ns chat.render-test
  (:require [chat.render :as render]
            [clojure.test :refer [deftest is testing]])
  (:import (net.kyori.adventure.text Component)
           (net.kyori.adventure.text.format NamedTextColor)
           (net.kyori.adventure.text.serializer.plain PlainTextComponentSerializer)))

(defn- plain [c] (.serialize (PlainTextComponentSerializer/plainText) c))

(deftest render-text-test
  (testing "placeholders are replaced"
    (is (= "Oi Ana, 3/20" (plain (render/render "Oi <player>, <online>/<max>" {:text {:player "Ana" :online 3 :max 20}})))))
  (testing "plain text values are never parsed as formatting"
    (let [c (render/render "<green><player>" {:text {:player "<red><bold>Admin"}})]
      (is (= "<red><bold>Admin" (plain c)))))
  (testing "template formatting still applies"
    (let [c (render/render "<green>ok" {})]
      (is (= NamedTextColor/GREEN (.color c)))))
  (testing "a missing placeholder stays as the literal tag text, not an exception"
    (is (string? (plain (render/render "x <nothing> y" {}))))))

(deftest parsed-values-test
  (testing "trusted parsed values carry their formatting"
    (let [c (render/render "<prefix> <player>" {:parsed {:prefix "<gold>[Lenda]</gold>"} :text {:player "Ana"}})]
      (is (= "[Lenda] Ana" (plain c)))
      (is (some #(= NamedTextColor/GOLD (.color ^Component %)) (.children c)))))
  (testing "an empty prefix leaves no stray tag"
    (is (= " Ana" (plain (render/render "<prefix> <player>" {:parsed {:prefix ""} :text {:player "Ana"}}))))))

(deftest component-values-test
  (testing "a chat message component is inserted as is"
    (let [msg (Component/text "oi <red>gente")
          c (render/render "<player>: <message>" {:text {:player "Ana"} :component {:message msg}})]
      (is (= "Ana: oi <red>gente" (plain c))))))

(deftest tps-text-test
  (is (= "20.0" (render/tps-text 20.0)))
  (is (= "20.0" (render/tps-text 25.7)) "capped at 20")
  (is (= "19.5" (render/tps-text 19.46)))
  (is (= "0.0" (render/tps-text 0))))

(deftest count-visible-test
  (is (= 3 (render/count-visible [:a :b :c] (constantly false))))
  (is (= 2 (render/count-visible [:a :b :c] #{:b})))
  (is (= 0 (render/count-visible [:a] #{:a})))
  (is (= 0 (render/count-visible [] #{:a}))))

(deftest scoreboard-entry-test
  (testing "entries are unique for the 15 possible lines and short"
    (let [entries (map render/scoreboard-entry (range 15))]
      (is (apply distinct? entries))
      (is (every? #(<= (count %) 40) entries)))))
