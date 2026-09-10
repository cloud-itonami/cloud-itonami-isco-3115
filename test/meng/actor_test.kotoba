(ns meng.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [meng.store :as store]
            [meng.advisor :as advisor]
            [meng.ledger :as led]
            [meng.actor :as actor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :name "Main Mechanical Assembly" :location "Plant A"})
    st))

(deftest run-request-accepts-test-record
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                  {:project-id "proj-1" :op :draft-test-record :stake :low}
                                  {}
                                  "thread-1")]
    (is (= :done (:status result)))
    (is (= 1 (count (store/records-of st "proj-1"))))))

(deftest run-request-escalates-mechanical-hazard
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                  {:project-id "proj-1" :op :flag-mechanical-hazard :stake :high}
                                  {}
                                  "thread-2")]
    (is (= :interrupted (:status result)))
    (is (empty? (store/records-of st "proj-1")))))

(deftest run-request-rejects-unregistered-project
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result (actor/run-request! graph
                                  {:project-id "no-such-project" :op :draft-test-record :stake :low}
                                  {}
                                  "thread-3")]
    (is (= :done (:status result)))
    (is (empty? (store/records-of st "no-such-project")))))

(deftest approve-resumes-and-commits
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
        result1 (actor/run-request! graph
                                   {:project-id "proj-1" :op :flag-mechanical-hazard :stake :high}
                                   {}
                                   "thread-4")]
    (is (= :interrupted (:status result1)))
    (is (empty? (store/records-of st "proj-1")))

    ;; approve and resume
    (let [result2 (actor/approve! graph "thread-4")]
      (is (= :done (:status result2)))
      (is (= 1 (count (store/records-of st "proj-1")))))))

(deftest audit-ledger-records-all-events
  (testing "`>= 1` was the whole of this assertion, which is true of a trail
            that records the decision and true of one that records only the
            write. Assert the sequence of entry types."
    (let [st (fresh-store)
          graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
          _ (actor/run-request! graph
                                {:project-id "proj-1" :op :draft-test-record :stake :low}
                                {}
                                "thread-5")]
      (is (= [:decided :committed] (mapv :ledger/type (store/ledger st))))
      (is (= ["thread-5" "thread-5"] (mapv :ledger/run-id (store/ledger st))))
      (is (:ok? (led/audit (store/ledger st)))))))

;; --- the audit holes, end to end -------------------------------------------
;; Measured on the tree before `meng.ledger` existed:
;;   A ordinary commit   -> [{:disposition :commit ..}]
;;   B hazard escalated,
;;     never signed off  -> []            <- nothing at all
;;   C hazard escalated,
;;     signed off        -> [{:disposition :commit ..}]   == A
;; These tests hold A != C and B != nothing.

(deftest escalation-is-on-the-record-before-anyone-signs-off
  (testing "the run interrupts for human sign-off and is never resumed; the
            trail must still name what it is waiting on. This is the case
            that used to leave the ledger EMPTY."
    (let [st (fresh-store)
          graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
          result (actor/run-request! graph
                                     {:project-id "proj-1"
                                      :op :flag-mechanical-hazard :stake :high}
                                     {}
                                     "thread-pending")]
      (is (= :interrupted (:status result)))
      (is (seq (store/ledger st)) "an escalation that records nothing is invisible")
      (is (= [:decided] (mapv :ledger/type (store/ledger st))))
      (is (= :request-approval
             (get-in (first (store/ledger st)) [:ledger/fact :disposition])))
      (is (= :awaiting-approval
             (get-in (led/runs (store/ledger st)) ["thread-pending" :state])))
      (is (= #{"thread-pending"} (led/unresolved (store/ledger st)))))))

(deftest a-signed-off-commit-is-distinguishable-from-an-unattended-one
  (testing "same store shape, two runs; the trail must not render them alike"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})]
      (actor/run-request! graph
                          {:project-id "proj-1" :op :draft-test-record :stake :low}
                          {} "run-unattended")
      (actor/run-request! graph
                          {:project-id "proj-1" :op :flag-mechanical-hazard :stake :high}
                          {} "run-signed")
      (actor/approve! graph "run-signed")
      (let [runs (led/runs (store/ledger st))]
        (is (= :committed (get-in runs ["run-unattended" :state])))
        (is (= :approved-committed (get-in runs ["run-signed" :state])))
        (is (not= (get-in runs ["run-unattended" :state])
                  (get-in runs ["run-signed" :state]))))
      (is (empty? (led/unresolved (store/ledger st))))
      (is (:ok? (led/audit (store/ledger st))))
      (is (= 2 (count (store/records-of st "proj-1")))))))

(deftest approval-entry-exists-only-where-approval-happened
  (let [st (fresh-store)
        graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})]
    (actor/run-request! graph
                        {:project-id "proj-1" :op :draft-test-record :stake :low}
                        {} "no-human")
    (is (not-any? #(= :approved (:ledger/type %)) (store/ledger st))
        "a run no human touched must not carry a sign-off entry")))

;; --- undeclared operations, end to end -------------------------------------

(deftest undeclared-operation-never-reaches-the-store
  (testing "measured before `meng.operation`: this committed a record at
            confidence 0.95"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
          result (actor/run-request! graph
                                     {:project-id "proj-1"
                                      :op :decommission-the-plant :stake :low}
                                     {} "thread-undeclared")]
      (is (= :done (:status result)))
      (is (empty? (store/records-of st "proj-1")))
      (is (= [:decided :held] (mapv :ledger/type (store/ledger st)))
          "the refusal is recorded, not merely performed")
      (is (some #(= :undeclared-operation (:rule %))
                (get-in (last (store/ledger st)) [:ledger/fact :violations]))
          "held for the reason this test is named after, not some other one"))))

(deftest nil-operation-is-refused-rather-than-thrown
  (testing "measured before `meng.operation`: (name nil) threw an NPE inside
            the advisor, so the governor never saw the request"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})
          result (actor/run-request! graph
                                     {:project-id "proj-1" :op nil :stake :low}
                                     {} "thread-nil")]
      (is (= :done (:status result)))
      (is (empty? (store/records-of st "proj-1")))
      (is (some #(= :undeclared-operation (:rule %))
                (get-in (last (store/ledger st)) [:ledger/fact :violations]))))))

(deftest a-hard-refusal-leaves-the-reason-in-the-trail
  (testing "an unregistered project is held, and the trail says why"
    (let [st (fresh-store)
          graph (actor/build-graph {:store st :advisor (advisor/mock-advisor)})]
      (actor/run-request! graph
                          {:project-id "no-such-project" :op :draft-test-record
                           :stake :low}
                          {} "thread-unregistered")
      (is (= [:decided :held] (mapv :ledger/type (store/ledger st))))
      (is (some #(= :no-project (:rule %))
                (get-in (last (store/ledger st)) [:ledger/fact :violations]))))))
