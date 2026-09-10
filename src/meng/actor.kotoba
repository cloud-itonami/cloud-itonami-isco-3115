(ns meng.actor
  "MenGActor — the ISCO-08 3115 mechanical engineering technician field
  test and inspection actor as a `langgraph.graph/state-graph` (per
  ADR-2607011000 / CLAUDE.md Actors section). One graph run = one test/inspection
  operation request (intake → advise → govern → decide → commit/hold, with a
  human-approval interrupt for escalated proposals). No infinite internal loop;
  checkpointed per superstep so an interrupted run can resume after human
  sign-off.

  ```text
  :intake -> :advise -> :govern -> :decide -+-> :commit           (:ok? true)
                                             +-> :request-approval  (:escalate? true, interrupt-before)
                                             +-> :hold              (:hard? true)
  ```

  The unconditional invariant: the TestAdvisor can never directly commit
  a record or dispatch a robot action the MenGGovernor refuses — every
  commit-record! call is gated behind `:decide`.

  The audit invariant: **every run writes its decision before anything
  can interrupt it.** `:decide` runs to completion in the superstep
  before `interrupt-before` stops the graph at `:request-approval`, so a
  hazard escalation that is never signed off still leaves a `:decided`
  entry naming the disposition it is waiting on. Appending at
  `:request-approval` instead would record nothing until a human
  resumed the thread, which is precisely backwards — the pending state
  is the one an auditor most needs to see. Measured before this rule
  existed: an escalated hazard left the ledger EMPTY (the measurement
  is in `meng.ledger`'s docstring)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [meng.advisor :as advisor]
            [meng.governor :as governor]
            [meng.store :as store]))

(defn build-graph
  "Build a compiled MenGActor graph. `store` implements
  `meng.store/Store`. `advisor` implements `meng.advisor/Advisor`
  (defaults to `mock-advisor`). `checkpointer` defaults to an in-memory one."
  [{:keys [store advisor checkpointer]
    :or {advisor (advisor/mock-advisor)
         checkpointer (cp/mem-checkpointer)}}]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}
         :run-id      {:default nil}
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :audit       {:reducer into :default []}}})
      (g/add-node :intake (fn [s] s))
      (g/add-node :advise
                   (fn [{:keys [request]}]
                     (let [p (advisor/-advise advisor store request)]
                       {:proposal p
                        :audit [{:node :advise :request request :proposal p}]})))
      (g/add-node :govern
                   (fn [{:keys [request context proposal]}]
                     (let [v (governor/check request context proposal store)]
                       {:verdict v
                        :audit [{:node :govern :verdict v}]})))
      (g/add-node :decide
                   (fn [{:keys [run-id verdict proposal]}]
                     (let [disposition (cond
                                         (:hard? verdict) :hold
                                         (:escalate? verdict) :request-approval
                                         :else :commit)]
                       ;; Written HERE, not at :request-approval — this
                       ;; superstep completes before interrupt-before stops
                       ;; the graph, so an escalation that is never signed
                       ;; off is still on the record.
                       (store/append-ledger! store run-id :decided
                                             {:disposition disposition
                                              :op (:op proposal)
                                              :verdict verdict})
                       {:disposition disposition
                        :audit [{:node :decide :disposition disposition}]})))
      (g/add-node :request-approval
                   (fn [{:keys [run-id proposal]}]
                     ;; interrupt-before stops the graph BEFORE this node,
                     ;; so reaching it means a human resumed the thread.
                     ;; That resumption IS the sign-off, and this entry is
                     ;; the only place it is recorded.
                     (store/append-ledger! store run-id :approved
                                           {:op (:op proposal)})
                     {:audit [{:node :request-approval :approved? true}]}))
      (g/add-node :commit
                   (fn [{:keys [run-id request proposal]}]
                     (let [record {:project-id (:project-id request)
                                    :op (:op proposal)
                                    :payload proposal}]
                       (store/commit-record! store record)
                       (store/append-ledger! store run-id :committed
                                             {:op (:op proposal) :record record})
                       {:record record
                        :audit [{:node :commit :record record}]})))
      (g/add-node :hold
                   (fn [{:keys [run-id verdict proposal]}]
                     (store/append-ledger! store run-id :held
                                           {:op (:op proposal)
                                            :violations (:violations verdict)
                                            :verdict verdict})
                     {:audit [{:node :hold :verdict verdict}]}))
      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges
       :decide
       (fn [{:keys [disposition]}]
         (case disposition
           :commit :commit
           :request-approval :request-approval
           :hold)))
      (g/add-edge :request-approval :commit)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)
      (g/compile-graph {:checkpointer checkpointer
                         :interrupt-before #{:request-approval}})))

(defn run-request!
  "Run one test/inspection operation request to completion or interrupt.
  `thread-id` scopes checkpointing for resume after human approval. Returns
  the full run result: `{:state .. :events .. :status :done|:interrupted :frontier ..}`."
  [graph request context thread-id]
  (g/run* graph {:request request :context context :run-id thread-id}
          {:thread-id thread-id}))

(defn approve!
  "Human-in-the-loop resume: the interrupted `:request-approval` node
  advances straight to `:commit` on resume (approval is the act of resuming
  the thread)."
  [graph thread-id]
  (g/run* graph nil {:thread-id thread-id :resume? true}))
