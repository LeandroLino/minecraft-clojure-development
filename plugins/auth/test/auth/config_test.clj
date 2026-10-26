(ns auth.config-test
  (:require [auth.logic :as logic]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [skycore.config :as cfg]
            [skycore.msg :as msg]))

(defn- resource-yaml [path]
  (cfg/parse-yaml (slurp (io/resource path))))

(deftest shipped-config-test
  (let [config (resource-yaml "config.yml")]
    (testing "the rules from the feature list"
      (is (= 6 (get-in config ["password" "min-length"])))
      (is (= 60 (get-in config ["login" "timeout-seconds"])))
      (is (= 3 (get-in config ["login" "max-attempts"])))
      (is (= 5 (get-in config ["login" "remember-minutes"]))))
    (testing "the iteration count is real, not a test value"
      (is (<= 100000 (get-in config ["password" "hash-iterations"]))))
    (testing "the shipped limits make sense together"
      (is (< (get-in config ["password" "min-length"]) (get-in config ["password" "max-length"])))
      (is (nil? (logic/password-error "seis66" (get-in config ["password" "min-length"]) (get-in config ["password" "max-length"])))))))

(deftest shipped-messages-test
  (let [messages (msg/flatten-keys (resource-yaml "lang/pt-BR.yml"))]
    (is (every? #(contains? messages (str "auth." %))
                ["welcome-register" "welcome-login" "welcome-unknown" "remembered" "reminder-register"
                 "reminder-login" "blocked" "registered-ok" "login-ok" "already-registered"
                 "already-logged-in" "not-registered" "password-mismatch" "password-too-short"
                 "password-too-long" "wrong-password" "too-many-attempts" "timeout" "name-taken"
                 "loading" "error"]))))
