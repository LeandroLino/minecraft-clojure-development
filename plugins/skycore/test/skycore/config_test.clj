(ns skycore.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [skycore.config :as cfg]))

(deftest parse-yaml-test
  (testing "nested maps keep string keys and integers become longs"
    (let [m (cfg/parse-yaml "grid:\n  spacing: 4\n  ratio: 0.5\nname: sky\n")]
      (is (= {"grid" {"spacing" 4 "ratio" 0.5} "name" "sky"} m))
      (is (instance? Long (get-in m ["grid" "spacing"])))))
  (testing "lists of scalars and of maps"
    (let [m (cfg/parse-yaml "weights:\n  - block: STONE\n    weight: 10\n  - block: SPONGE\n    weight: 1\nnames: [a, b]\n")]
      (is (= [{"block" "STONE" "weight" 10} {"block" "SPONGE" "weight" 1}] (get m "weights")))
      (is (= ["a" "b"] (get m "names")))))
  (testing "blank text is an empty map"
    (is (= {} (cfg/parse-yaml ""))))
  (testing "invalid YAML throws"
    (is (thrown? Exception (cfg/parse-yaml "a: [unclosed")))))

(deftest parse-edn-test
  (is (= {:a 1 :b [2 3]} (cfg/parse-edn "{:a 1 :b [2 3]}")))
  (is (nil? (cfg/parse-edn ""))))

(deftest deep-merge-test
  (testing "the second map wins, nested maps are merged"
    (is (= {"a" {"x" 1 "y" 20} "b" 3}
           (cfg/deep-merge {"a" {"x" 1 "y" 2}} {"a" {"y" 20} "b" 3}))))
  (testing "lists are replaced, not concatenated"
    (is (= {"l" [9]} (cfg/deep-merge {"l" [1 2]} {"l" [9]}))))
  (testing "defaults survive when the admin's file lacks a key"
    (is (= {"db" {"timeout" 5 "new" 7}}
           (cfg/deep-merge {"db" {"timeout" 5 "new" 7}} {"db" {"timeout" 5}})))))

(deftest lookup-test
  (let [m {"grid" {"spacing" 4 "off" false}}]
    (is (= 4 (cfg/lookup m "grid.spacing" ::none)))
    (is (= 4 (cfg/lookup m ["grid" "spacing"] ::none)))
    (testing "false is a value, not a missing key"
      (is (= false (cfg/lookup m "grid.off" ::none))))
    (is (= ::none (cfg/lookup m "grid.missing" ::none)))
    (is (= ::none (cfg/lookup nil "grid.spacing" ::none)))))
