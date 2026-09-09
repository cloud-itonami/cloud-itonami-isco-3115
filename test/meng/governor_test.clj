(ns meng.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [meng.store :as store]
            [meng.operation :as operation]
            [meng.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :name "Main Mechanical Assembly" :location "Plant A"})
    st))

(deftest ok-on-clean-test-record
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest ok-on-inspection-logging
  (let [st (fresh-store)
        proposal {:op :log-inspection-data :effect :propose :confidence 0.85 :stake :medium}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest ok-on-site-visit-scheduling
  (let [st (fresh-store)
        proposal {:op :schedule-site-visit :effect :propose :confidence 0.8 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:ok? v))
    (is (not (:hard? v)))
    (is (not (:escalate? v)))))

(deftest hard-on-unregistered-project
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.9 :stake :low}
        v (governor/check {:project-id "no-such-project"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-project (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :direct-write :confidence 0.9 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest escalates-on-mechanical-hazard
  (let [st (fresh-store)
        proposal {:op :flag-mechanical-hazard :effect :propose :confidence 0.9 :stake :high}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest escalates-on-low-confidence
  (let [st (fresh-store)
        proposal {:op :draft-test-record :effect :propose :confidence 0.2 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))

(deftest store-records-and-ledger-append-only
  (let [st (fresh-store)]
    (store/commit-record! st {:project-id "proj-1" :op :draft-test-record})
    (store/append-ledger! st "run-1" :committed {:op :draft-test-record})
    (is (= 1 (count (store/records-of st "proj-1"))))
    (is (= 1 (count (store/ledger st))))
    ;; the store types the entry — a caller cannot append a bare map
    (is (= :committed (:ledger/type (first (store/ledger st)))))
    (is (= "run-1" (:ledger/run-id (first (store/ledger st)))))))

;; --- undeclared operations -------------------------------------------------
;; Measured on the tree before `meng.operation` existed:
;;   (run-request! g {:project-id "p1" :op :decommission-the-plant :stake :low} ..)
;;   => :done, one committed record, confidence 0.95.
;; The rule name is pinned, not just `:hard?` — a proposal can be hard-held
;; for four different reasons, and asserting only that it was held would let
;; this test pass on a run refused for some other reason entirely.

(deftest hard-on-undeclared-operation
  (let [st (fresh-store)
        proposal {:op :decommission-the-plant :effect :propose :confidence 0.95 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:hard? v) "an op no catalog declares must never commit")
    (is (not (:escalate? v))
        "held, not escalated — there is nothing for a human to approve")
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))))

(deftest hard-on-nil-operation
  (let [st (fresh-store)
        proposal {:op nil :effect :propose :confidence 0.95 :stake :low}
        v (governor/check {:project-id "proj-1"} {} proposal st)]
    (is (:hard? v))
    (is (some #(= :undeclared-operation (:rule %)) (:violations v)))))

(deftest every-declared-op-is-assessable
  (testing "no declared op is hard-held for being undeclared — otherwise the
            catalog and the rule that reads it have drifted apart"
    (let [st (fresh-store)]
      (doseq [op operation/ops]
        (let [v (governor/check {:project-id "proj-1"} {}
                                {:op op :effect :propose :confidence 0.95 :stake :low}
                                st)]
          (is (not (some #(= :undeclared-operation (:rule %)) (:violations v)))
              (str op " is declared but the governor treated it as undeclared")))))))

(deftest escalating-property-comes-from-the-catalog
  (testing "the governor escalates exactly the ops the catalog marks
            :escalates? — checked in BOTH directions so neither a catalog
            entry the governor ignores nor a governor rule the catalog does
            not declare can pass quietly"
    (let [st (fresh-store)]
      (doseq [op operation/ops]
        (let [v (governor/check {:project-id "proj-1"} {}
                                ;; confidence above the floor, so escalation
                                ;; here can only come from the op itself
                                {:op op :effect :propose :confidence 0.95 :stake :low}
                                st)]
          (is (= (operation/escalating? op) (boolean (:escalate? v)))
              (str op ": catalog says :escalates? " (operation/escalating? op)
                   " but the governor said " (boolean (:escalate? v)))))))))

;; --- the confidence floor is a boundary, so put an input exactly on it ------
;; CLAUDE.md: 比較を持つ検査には、必ず境界ちょうどの入力を 1 つ置く。
;; Without this case, flipping `<` to `<=` in the governor leaves the suite green.

(deftest confidence-exactly-at-the-floor-does-not-escalate
  (let [st (fresh-store)
        at-floor {:op :draft-test-record :effect :propose
                  :confidence governor/confidence-floor :stake :low}
        v (governor/check {:project-id "proj-1"} {} at-floor st)]
    (is (not (:escalate? v))
        "the floor is inclusive: confidence == confidence-floor is acceptable")
    (is (:ok? v))))

(deftest confidence-just-below-the-floor-escalates
  (let [st (fresh-store)
        below {:op :draft-test-record :effect :propose
               :confidence (- governor/confidence-floor 0.01) :stake :low}
        v (governor/check {:project-id "proj-1"} {} below st)]
    (is (:escalate? v))
    (is (not (:ok? v)))))
