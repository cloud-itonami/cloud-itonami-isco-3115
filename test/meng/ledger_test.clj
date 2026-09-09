(ns meng.ledger-test
  "The trail's own structure, and the two states it could not express
  before `meng.ledger` existed. See that namespace's docstring for the
  measurement these tests hold in place."
  (:require [clojure.test :refer [deftest is testing]]
            [meng.ledger :as led]))

(deftest append-assigns-its-own-sequence
  (testing "a caller cannot choose an entry's position"
    (let [entries (-> [] (led/append "r1" :decided {:disposition :commit})
                      (led/append "r1" :committed {:op :draft-test-record}))]
      (is (= [0 1] (mapv :ledger/seq entries)))
      (is (= ["r1" "r1"] (mapv :ledger/run-id entries)))
      (is (= [:decided :committed] (mapv :ledger/type entries))))))

(deftest append-is-pure
  (let [before (led/append [] "r1" :decided {:disposition :commit})
        after  (led/append before "r1" :committed {})]
    (is (= 1 (count before)) "append must not mutate the vector it was given")
    (is (= 2 (count after)))))

;; --- the state that used to be invisible -----------------------------------

(deftest escalated-and-unsigned-is-a-reportable-state
  (testing "a run that was escalated and never signed off reports as
            :awaiting-approval and appears in `unresolved` — before this
            namespace it wrote nothing at all and was indistinguishable
            from a run that never happened"
    (let [entries (led/append [] "r-pending" :decided
                              {:disposition :request-approval
                               :op :flag-mechanical-hazard})]
      (is (= :awaiting-approval (get-in (led/runs entries) ["r-pending" :state])))
      (is (= #{"r-pending"} (led/unresolved entries))))))

(deftest signed-off-commit-is-distinguishable-from-an-ordinary-one
  (testing "the two commits differ in the trail, not only in their payload"
    (let [ordinary (-> [] (led/append "r-plain" :decided {:disposition :commit})
                       (led/append "r-plain" :committed {:op :draft-test-record}))
          signed   (-> [] (led/append "r-signed" :decided
                                      {:disposition :request-approval
                                       :op :flag-mechanical-hazard})
                       (led/append "r-signed" :approved {:op :flag-mechanical-hazard})
                       (led/append "r-signed" :committed {:op :flag-mechanical-hazard}))]
      (is (= :committed (get-in (led/runs ordinary) ["r-plain" :state])))
      (is (= :approved-committed (get-in (led/runs signed) ["r-signed" :state])))
      (is (not= (get-in (led/runs ordinary) ["r-plain" :state])
                (get-in (led/runs signed) ["r-signed" :state]))))))

(deftest a-resolved-escalation-leaves-unresolved
  (let [entries (-> [] (led/append "r" :decided {:disposition :request-approval})
                    (led/append "r" :approved {})
                    (led/append "r" :committed {}))]
    (is (empty? (led/unresolved entries)))))

(deftest held-run-is-not-awaiting-anyone
  (let [entries (-> [] (led/append "r" :decided {:disposition :hold})
                    (led/append "r" :held {:violations [{:rule :no-project}]}))]
    (is (= :held (get-in (led/runs entries) ["r" :state])))
    (is (empty? (led/unresolved entries)))))

(deftest runs-separates-concurrent-threads
  (let [entries (-> [] (led/append "a" :decided {:disposition :commit})
                    (led/append "b" :decided {:disposition :request-approval})
                    (led/append "a" :committed {:op :draft-test-record}))]
    (is (= #{"a" "b"} (set (keys (led/runs entries)))))
    (is (= :committed (get-in (led/runs entries) ["a" :state])))
    (is (= :awaiting-approval (get-in (led/runs entries) ["b" :state])))
    (is (= #{"b"} (led/unresolved entries)))))

;; --- audit reports counts, and each problem kind at least once -------------
;; CLAUDE.md: その検査は、自分が名乗っている理由で拒否したことがあるか。
;; Each case below asserts the SPECIFIC :kind, so a check that fires for
;; some other reason cannot be counted as this one discriminating.

(deftest audit-is-clean-on-a-well-formed-trail
  (let [entries (-> [] (led/append "r" :decided {:disposition :commit})
                    (led/append "r" :committed {:op :draft-test-record}))
        a (led/audit entries)]
    (is (:ok? a))
    (is (zero? (:problem-count a)))
    (is (= 2 (:count a)))
    (is (= 1 (:runs a)))))

(deftest audit-is-clean-on-an-empty-trail-but-says-so-in-counts
  (testing "an empty trail is structurally fine; :count 0 is what tells the
            reader nothing was recorded. A bare `:ok? true` would read the
            same as a fully audited actor."
    (let [a (led/audit [])]
      (is (:ok? a))
      (is (zero? (:count a)))
      (is (zero? (:runs a))))))

(deftest audit-catches-a-reordered-trail
  (let [entries (-> [] (led/append "r" :decided {:disposition :commit})
                    (led/append "r" :committed {}))
        tampered (vec (reverse entries))
        a (led/audit tampered)]
    (is (not (:ok? a)))
    (is (some #(= :seq-out-of-order (:kind %)) (:problems a)))))

(deftest audit-catches-an-unknown-entry-type
  (let [a (led/audit [(led/entry 0 "r" :teleported {})])]
    (is (not (:ok? a)))
    (is (some #(= :unknown-entry-type (:kind %)) (:problems a)))))

(deftest audit-catches-a-missing-run-id
  (let [a (led/audit [(led/entry 0 nil :decided {:disposition :commit})])]
    (is (not (:ok? a)))
    (is (some #(= :missing-run-id (:kind %)) (:problems a)))))

(deftest audit-catches-a-commit-no-decision-authorised
  (testing "a committed record with no :decided entry before it is a write
            no disposition authorised"
    (let [a (led/audit (led/append [] "r" :committed {:op :draft-test-record}))]
      (is (not (:ok? a)))
      (is (some #(= :commit-not-decided (:kind %)) (:problems a))))))

(deftest audit-catches-a-sign-off-nobody-asked-for
  (let [entries (-> [] (led/append "r" :decided {:disposition :commit})
                    (led/append "r" :approved {})
                    (led/append "r" :committed {}))
        a (led/audit entries)]
    (is (not (:ok? a)))
    (is (some #(= :approved-not-escalated (:kind %)) (:problems a)))))

(deftest audit-counts-problems-rather-than-answering-yes-or-no
  (testing "one broken entry must be distinguishable from a wholly broken
            trail — a boolean cannot say which of these happened"
    (let [one  (led/audit [(led/entry 0 "r" :teleported {})])
          many (led/audit [(led/entry 0 nil :teleported {})
                           (led/entry 5 nil :warped {})])]
      (is (= 1 (:problem-count one)))
      (is (< (:problem-count one) (:problem-count many))))))
