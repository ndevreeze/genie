(ns genied.core-test
  (:require [clojure.test :refer :all] ;; use standard test...
            [midje.sweet :as midje]    ;; ... or midje.
            [genied.core :refer :all]))

(midje/facts
 "Test several facts"
 (midje/fact "Basic test"
             (* 6 7)
             => 42))
