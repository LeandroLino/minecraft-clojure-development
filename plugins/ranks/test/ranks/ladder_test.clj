(ns ranks.ladder-test
  (:require [clojure.test :refer [deftest is testing]]
            [ranks.ladder :as ladder]))

(def cfg
  [{"id" "novato" "name" "Novato" "prefix" "<gray>[N]" "price" 0
    "permissions" ["a.one"] "limits" {"homes" 2 "claims" 1}}
   {"id" "medio" "name" "Médio" "prefix" "<green>[M]" "price" 500
    "permissions" ["b.two"] "limits" {"homes" 4}}
   {"id" "topo" "name" "Topo" "price" 9000
    "permissions" ["a.one" "c.three"] "limits" {"claims" 9}}])

(def l (ladder/parse cfg))

(defn- errors-of [c]
  (try (ladder/parse c) nil
       (catch clojure.lang.ExceptionInfo e (:errors (ex-data e)))))

(deftest parse-valid-test
  (is (= ["novato" "medio" "topo"] (map :id l)))
  (is (= [0 500 9000] (map :price l)))
  (testing "missing optional fields get defaults"
    (is (= "" (:prefix (nth l 2))))
    (is (= "Topo" (:name (nth l 2))))))

(deftest navigation-test
  (is (= 1 (ladder/position l "medio")))
  (is (nil? (ladder/position l "nope")))
  (is (= "medio" (:id (ladder/next-rank l "novato"))))
  (is (= "topo" (:id (ladder/next-rank l "medio"))))
  (testing "no rank after the last one or after an unknown id"
    (is (nil? (ladder/next-rank l "topo")))
    (is (nil? (ladder/next-rank l "nope")))))

(deftest effective-test
  (testing "the first rank has only its own grants"
    (is (= {:permissions #{"a.one"} :limits {"homes" 2 "claims" 1}} (ladder/effective l "novato"))))
  (testing "permissions accumulate, limits override but carry over"
    (is (= #{"a.one" "b.two"} (:permissions (ladder/effective l "medio"))))
    (is (= {"homes" 4 "claims" 1} (:limits (ladder/effective l "medio"))))
    (is (= #{"a.one" "b.two" "c.three"} (:permissions (ladder/effective l "topo"))))
    (is (= {"homes" 4 "claims" 9} (:limits (ladder/effective l "topo")))))
  (testing "an unknown rank id falls back to the first rank"
    (is (= (ladder/effective l "novato") (ladder/effective l "removido")))))

(deftest limit-test
  (is (= 4 (ladder/limit l "medio" :homes)))
  (is (= 4 (ladder/limit l "medio" "homes")))
  (is (= 9 (ladder/limit l "topo" :claims)))
  (is (nil? (ladder/limit l "topo" :warps))))

(deftest invalid-config-test
  (testing "problems are all reported"
    (let [errors (errors-of [{"id" "Bad Id" "price" 5}
                             {"id" "x" "price" -1}
                             {"id" "x" "price" "free"}])]
      (is (some #(re-find #"ranks\.0\.id" %) errors))
      (is (some #(re-find #"ranks\.1\.price" %) errors))
      (is (some #(re-find #"ranks\.2\.price" %) errors))
      (is (some #(re-find #"duplicate ids" %) errors))
      (is (some #(re-find #"must cost 0" %) errors))))
  (testing "the first rank must be free"
    (is (some #(re-find #"must cost 0" %) (errors-of [{"id" "a" "price" 10}]))))
  (testing "empty or missing list"
    (is (some #(re-find #"non-empty list" %) (errors-of [])))
    (is (some #(re-find #"non-empty list" %) (errors-of nil))))
  (testing "bad permissions and limits"
    (is (some #(re-find #"permissions" %) (errors-of [{"id" "a" "price" 0 "permissions" "x"}])))
    (is (some #(re-find #"limits" %) (errors-of [{"id" "a" "price" 0 "limits" {"homes" "many"}}])))))
