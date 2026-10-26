(ns teleport.load-test
  (:require [clojure.test :refer [deftest is]]))

(deftest entry-namespace-compiles-test
  ;; Catches compile errors and reflection warnings that only the server would show.
  (require 'teleport.core :reload-all)
  (is (some? (find-ns 'teleport.core))))
