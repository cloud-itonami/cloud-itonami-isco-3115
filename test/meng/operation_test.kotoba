(ns meng.operation-test
  "The declared operation vocabulary. These tests are the thing that makes
  the catalog load-bearing rather than documentation: they fail in BOTH
  directions, so neither an op the catalog declares and nothing uses, nor
  an op the code uses and the catalog never declared, can pass quietly."
  (:require [clojure.test :refer [deftest is testing]]
            [meng.advisor :as advisor]
            [meng.store :as store]
            [meng.operation :as operation]))

(deftest catalog-is-not-empty
  (testing "an empty catalog would make `known?` false for everything and
            hard-hold every run — which reads as a very strict actor rather
            than a broken one. Assert the count, not just non-emptiness."
    (is (pos? (count operation/catalog)))
    (is (= (count operation/catalog) (count operation/ops)))))

(deftest every-entry-declares-the-fields-the-governor-reads
  (doseq [[op decl] operation/catalog]
    (testing (str op)
      (is (contains? decl :escalates?)
          (str op " does not declare :escalates?; `escalating?` would silently
               read it as false and the governor would let it through"))
      (is (boolean? (:escalates? decl)))
      (is (string? (:label decl)))
      (is (string? (:description decl))))))

(deftest known?-is-false-for-things-nobody-declared
  (is (not (operation/known? :decommission-the-plant)))
  (is (not (operation/known? nil)))
  (is (not (operation/known? "draft-test-record"))
      "a string is not the keyword the graph routes on"))

(deftest known?-is-true-for-every-declared-op
  (doseq [op operation/ops]
    (is (operation/known? op))))

(deftest escalating?-is-false-for-an-undeclared-op
  (testing "an undeclared op must be HELD, not escalated — answering true
            here would route it to a human with nothing to approve"
    (is (not (operation/escalating? :decommission-the-plant)))
    (is (not (operation/escalating? nil)))))

(deftest hazard-flagging-always-requires-sign-off
  (testing "the README's robotics premise, pinned to the catalog entry that
            implements it"
    (is (operation/escalating? :flag-mechanical-hazard))))

(deftest describe-never-returns-nil
  (testing "a report that silently omits the op it could not name reads as a
            report about fewer ops rather than one with an unknown in it"
    (doseq [op operation/ops]
      (is (string? (operation/describe op))))
    (is (string? (operation/describe :decommission-the-plant)))
    (is (re-find #"UNDECLARED" (operation/describe :decommission-the-plant)))
    (is (string? (operation/describe nil)))))

(deftest undeclared-reports-which-ops-not-merely-that-there-were-some
  (let [u (operation/undeclared [:draft-test-record :decommission-the-plant nil])]
    (is (= 2 (count u)))
    (is (contains? u :decommission-the-plant))
    (is (contains? u nil))
    (is (not (contains? u :draft-test-record))))
  (is (empty? (operation/undeclared operation/ops))))

;; --- the advisor and the catalog must not drift apart ----------------------

(deftest mock-advisor-proposes-every-declared-op-at-usable-confidence
  (testing "an op the catalog declares must survive the advisor — otherwise
            the catalog names operations the actor can never actually run"
    (let [st (store/mem-store)
          adv (advisor/mock-advisor)]
      (doseq [op operation/ops]
        (let [p (advisor/-advise adv st {:project-id "p1" :op op :stake :low})]
          (is (= op (:op p)))
          (is (= :propose (:effect p)))
          (is (pos? (:confidence p))
              (str op " is declared but the advisor gave it confidence 0")))))))

(deftest mock-advisor-degrades-an-undeclared-op-instead-of-throwing
  (testing "measured before the catalog existed: (name nil) threw an NPE inside
            `infer`, so a malformed request never reached the governor at all"
    (let [st (store/mem-store)
          adv (advisor/mock-advisor)]
      (doseq [op [:decommission-the-plant nil]]
        (let [p (advisor/-advise adv st {:project-id "p1" :op op :stake :low})]
          (is (= op (:op p)) "the undeclared op is carried through, not swallowed")
          (is (= :propose (:effect p)))
          (is (zero? (:confidence p))
              "never fabricate confidence for something with no declared meaning"))))))
