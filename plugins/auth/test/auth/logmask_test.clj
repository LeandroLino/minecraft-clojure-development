(ns auth.logmask-test
  (:require [auth.logmask :as logmask]
            [clojure.test :refer [deftest is testing]]))

(deftest mask-hides-passwords-test
  (testing "the arguments of /login and /register are replaced"
    (is (= "Ana issued server command: /login ***" (logmask/mask "Ana issued server command: /login hunter2")))
    (is (= "Ana issued server command: /register ***" (logmask/mask "Ana issued server command: /register hunter2 hunter2")))
    (is (= "Ana issued server command: /LOGIN ***" (logmask/mask "Ana issued server command: /LOGIN Segredo")))
    (is (= "Ana issued server command: /auth:login ***" (logmask/mask "Ana issued server command: /auth:login x y"))))
  (testing "/sky senha keeps the player and hides the new password"
    (is (= "Op issued server command: /sky senha Ana ***" (logmask/mask "Op issued server command: /sky senha Ana nova-senha-1"))))
  (testing "the text before the command is untouched, even with spaces and symbols"
    (is (= "[Ana] issued server command: /login ***" (logmask/mask "[Ana] issued server command: /login a b c")))))

(deftest mask-leaves-everything-else-test
  (doseq [line ["Ana issued server command: /home casa"
                "Ana issued server command: /login"
                "Ana issued server command: /sky status"
                "Ana issued server command: /sky senha Ana"
                "Ana lost connection: Disconnected"
                "<Ana> /login hunter2 (in chat, not a command)"
                ""]]
    (is (= line (logmask/mask line)) line))
  (is (nil? (logmask/mask nil))))

(deftest mask-is-idempotent-test
  (let [once (logmask/mask "Ana issued server command: /login hunter2")]
    (is (= once (logmask/mask once)))
    (testing "so a masked line written back to the log is not masked, or swallowed, again"
      (is (= "Op issued server command: /sky senha Ana ***"
             (logmask/mask (logmask/mask "Op issued server command: /sky senha Ana x")))))))

(deftest format-params-test
  (is (= "Ana issued server command: /login x" (logmask/format-params "{} issued server command: {}" ["Ana" "/login x"])))
  (is (= "sem parâmetros" (logmask/format-params "sem parâmetros" nil)))
  (testing "missing parameters leave the braces, extra ones are ignored"
    (is (= "a {} b" (logmask/format-params "{} {} b" ["a"])))
    (is (= "a" (logmask/format-params "{}" ["a" "b"]))))
  (testing "a Log4j2 style line gets masked once formatted"
    (is (= "Ana issued server command: /login ***"
           (logmask/mask (logmask/format-params "{} issued server command: {}" ["Ana" "/login hunter2"]))))))
