(ns tensors.rnn-test
  (:require [tensors.rnn :as rnn :refer :all]
            [clojure.test :as t :refer :all]
            [tensors.model :as model]
            [tensors.neanderthal-ops :as no]
            [tensors.computation-graph :as cg]
            [tensors.node :as node]
            [tensors.core :as tensors]
            [tensors.compute :as compute]))

(deftest lstm-cell-test
  (let [factory (no/factory)
        m (model/simple-param-collection factory)
        cell (lstm-cell m 50 10)
        zero  (tensors/zeros factory [10])
        init-output (node/constant  factory "h0" zero)
        init-state (node/constant factory "c0"  zero)
        input (node/constant factory "x"  (repeat 50 1))
        [output state] (add-input! cell input init-output init-state)]
    (is (= (:shape output) [10]))
    (is (= (:shape state) [10]))
    (is (=
         [10]
         (tensors/shape factory (:value (compute/forward-pass! factory output)))))))
