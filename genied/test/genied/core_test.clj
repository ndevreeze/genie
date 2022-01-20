(ns genied.core-test
  "No real tests for now. Most functions are stateful, so harder to test.
   Main testing is done with the scripts in the test-directory."
  (:require [midje.sweet :as midje]
            [genied.client :as client]
            [genied.core :as core]))

;; setup daemon for testing
(core/pre-init-daemon {} [] {})
(core/post-init-daemon {} [] {})

(declare =>)

(midje/facts
 "Test calling exec-script with test-scripts"

 (midje/fact "Test calling test.clj"
             (with-out-str
               (client/exec-script "../test/test.clj"
                                   'test/main
                                   {:protocol-version "0.1.0"
                                    :opt {}} []))
             => (str "Just a simple line to stdout\nctx:"
                     "  {:protocol-version 0.1.0, :opt {}}\n")))
