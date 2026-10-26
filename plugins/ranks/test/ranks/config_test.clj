(ns ranks.config-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ranks.ladder :as ladder]
            [skycore.config :as cfg]))

(deftest shipped-config-is-valid-test
  ;; The ladder the jar ships must pass the same validator the plugin runs in `enable`; an
  ;; id with capital letters used to keep the whole module from starting.
  (let [config (cfg/parse-yaml (slurp (io/resource "config.yml")))
        l (ladder/parse (get config "ranks"))]
    (testing "five ranks, the last one is ultimate"
      (is (= ["novato" "aventureiro" "veterano" "lenda" "ultimate"] (map :id l)))
      (is (zero? (:price (first l)))))))
