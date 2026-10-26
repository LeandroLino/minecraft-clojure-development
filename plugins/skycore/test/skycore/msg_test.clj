(ns skycore.msg-test
  (:require [clojure.test :refer [deftest is testing]]
            [skycore.msg :as msg])
  (:import (net.kyori.adventure.text.minimessage MiniMessage)
           (net.kyori.adventure.text.serializer.plain PlainTextComponentSerializer)))

(def ^:private escape @#'msg/escape-tags)

(deftest flatten-keys-test
  (is (= {"prefix" "p" "core.usage" "u" "a.b.c" "1"}
         (msg/flatten-keys {"prefix" "p" "core" {"usage" "u"} "a" {"b" {"c" 1}}}))))

(deftest fill-test
  (testing "known placeholders are replaced, keyword or string keys"
    (is (= "Oi Maria, 10" (msg/fill "Oi {player}, {n}" {:player "Maria" "n" 10} identity))))
  (testing "unknown placeholders are left alone"
    (is (= "x {other}" (msg/fill "{a} {other}" {:a "x"} identity))))
  (testing "values are not re-scanned for placeholders"
    (is (= "{b} B" (msg/fill "{a} {b}" {:a "{b}" :b "B"} identity))))
  (testing "regex and replacement metacharacters in values are literal"
    (is (= "a$1\\b" (msg/fill "{v}" {:v "a$1\\b"} identity)))))

(deftest escaping-blocks-formatting-injection-test
  (let [text (msg/fill "<green>{player}" {:player "<red><bold>Admin"} escape)
        plain (.serialize (PlainTextComponentSerializer/plainText)
                          (.deserialize (MiniMessage/miniMessage) text))]
    (testing "a player-supplied value is shown literally, not parsed as MiniMessage"
      (is (= "<red><bold>Admin" plain)))))
