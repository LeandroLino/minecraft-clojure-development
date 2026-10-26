(ns auth.password-test
  (:require [auth.password :as password]
            [clojure.test :refer [deftest is testing]]))

;; few iterations: the tests check the logic, not the cost
(def iterations 1000)

(deftest create-and-verify-test
  (let [record (password/create "segredo123" iterations)]
    (testing "the right password verifies, any other does not"
      (is (password/verify "segredo123" record))
      (is (not (password/verify "segredo124" record)))
      (is (not (password/verify "" record)))
      (is (not (password/verify "SEGREDO123" record))))
    (testing "the record never holds the password"
      (is (= #{:salt :hash :iterations} (set (keys record))))
      (is (not-any? #(re-find #"segredo" (str %)) (vals record))))
    (testing "the iterations are kept, so each hash can be checked with its own cost"
      (is (= iterations (:iterations record)))
      (let [other (password/create "segredo123" 2000)]
        (is (password/verify "segredo123" other))
        (is (not (password/verify "segredo123" (assoc other :iterations iterations))))))))

(deftest salted-test
  (let [a (password/create "mesma-senha" iterations)
        b (password/create "mesma-senha" iterations)]
    (testing "two players with the same password get different salts and hashes"
      (is (not= (:salt a) (:salt b)))
      (is (not= (:hash a) (:hash b))))
    (testing "and each one still verifies"
      (is (password/verify "mesma-senha" a))
      (is (password/verify "mesma-senha" b)))))

(deftest odd-passwords-test
  (testing "accents, spaces and long passwords"
    (doseq [pw ["ação com espaço" "🔒senha🔒" (apply str (repeat 64 "x"))]]
      (let [record (password/create pw iterations)]
        (is (password/verify pw record) pw))))
  (testing "a missing record or password verifies nothing"
    (is (not (password/verify "x" nil)))
    (is (not (password/verify nil (password/create "x" iterations))))))
