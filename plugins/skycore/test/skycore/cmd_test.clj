(ns skycore.cmd-test
  (:require [clojure.test :refer [deftest is]]
            [skycore.cmd :as cmd]))

(deftest complete-prefix-test
  (is (= ["alan" "Alice"] (cmd/complete-prefix ["Bob" "Alice" "alan"] "al")))
  (is (= ["alan" "Alice" "Bob"] (cmd/complete-prefix ["Bob" "Alice" "alan"] "")))
  (is (= [] (cmd/complete-prefix ["Bob"] "z"))))
