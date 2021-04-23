(ns genied.core-test
  "No real tests for now. Most functions are stateful, so harder to test.
   Main testing is done with the scripts in the test-directory."
  (:require [clojure.test :refer :all] ;; use standard test...
            [midje.sweet :as midje]    ;; ... or midje.
            [genied.core :refer :all]))

(midje/facts
 "Test several facts"
 (midje/fact "Basic test"
             (* 6 7)
             => 42))
