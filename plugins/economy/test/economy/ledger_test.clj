(ns economy.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [economy.ledger :as ledger]))

(deftest parse-amount-test
  (testing "positive whole numbers"
    (is (= 1 (ledger/parse-amount "1")))
    (is (= 1250 (ledger/parse-amount "1250")))
    (is (= 7 (ledger/parse-amount "007"))))
  (testing "everything else is rejected"
    (doseq [s [nil "" "0" "-5" "+5" "1.5" "1,5" "abc" "12abc" " 5" "5 " "1e3"
               "9999999999999999999" "1234567890123456789"]]
      (is (nil? (ledger/parse-amount s)) (pr-str s)))))

(deftest deposit-test
  (is (= {:ok 150} (ledger/deposit 100 50 1000)))
  (testing "the limit is inclusive"
    (is (= {:ok 1000} (ledger/deposit 990 10 1000)))
    (is (= {:error :overflow} (ledger/deposit 990 11 1000))))
  (testing "no long overflow even near Long/MAX_VALUE"
    (is (= {:error :overflow} (ledger/deposit Long/MAX_VALUE 1 Long/MAX_VALUE)))
    (is (= {:error :overflow} (ledger/deposit 5 Long/MAX_VALUE Long/MAX_VALUE))))
  (testing "non-positive amounts"
    (is (= {:error :invalid} (ledger/deposit 100 0 1000)))
    (is (= {:error :invalid} (ledger/deposit 100 -5 1000)))))

(deftest withdraw-test
  (is (= {:ok 60} (ledger/withdraw 100 40)))
  (is (= {:ok 0} (ledger/withdraw 100 100)))
  (is (= {:error :insufficient} (ledger/withdraw 100 101)))
  (is (= {:error :invalid} (ledger/withdraw 100 0)))
  (is (= {:error :invalid} (ledger/withdraw 100 -1))))

(deftest transfer-test
  (testing "moves exactly the amount and keeps the total"
    (let [{:keys [ok]} (ledger/transfer 100 20 30 1000)]
      (is (= {:from 70 :to 50} ok))
      (is (= 120 (+ (:from ok) (:to ok))))))
  (testing "fails without changing anything the caller can see"
    (is (= {:error :insufficient} (ledger/transfer 10 20 30 1000)))
    (is (= {:error :overflow} (ledger/transfer 100 990 30 1000)))
    (is (= {:error :invalid} (ledger/transfer 100 20 0 1000)))))

(deftest format-test
  (is (= "1.250" (ledger/format-number 1250)))
  (is (= "0" (ledger/format-number 0)))
  (is (= "1.000.000" (ledger/format-number 1000000)))
  (is (= "$1.250" (ledger/format-amount "$" 1250))))
