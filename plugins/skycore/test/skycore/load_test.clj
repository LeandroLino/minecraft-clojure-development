(ns skycore.load-test
  (:require [clojure.test :refer [deftest is]]))

(deftest entry-namespace-compiles-test
  ;; Catches compile errors and reflection warnings that only the server would show.
  (require 'skycore.core :reload-all)
  (is (some? (find-ns 'skycore.cache))))
