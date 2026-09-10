(ns meng.governor
  "MenGGovernor — the independent safety/traceability layer for
  the ISCO-08 3115 mechanical engineering technician field test and inspection
  actor. Wired as its own `:govern` node in `meng.actor`'s StateGraph,
  downstream of `:advise` — the Advisor has no notion of project provenance or
  mechanical-hazard risk, so this MUST be a separate system able to
  reject a proposal (itonami actor pattern, per ADR-2607011000 / CLAUDE.md
  Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. project provenance  — the request's project must be registered.
    2. no-actuation         — proposal :effect must be :propose.
    3. declared operation   — the proposal's :op must be in
       `meng.operation/catalog`. Measured before this rule existed:
       `:op :decommission-the-plant` committed at confidence 0.95,
       because nothing asked whether the op meant anything. It is a HOLD
       rather than an escalation on purpose — an operation whose meaning
       is undeclared cannot be put in front of a human as \"approve
       this?\", since there is nothing to approve.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off, per the
  README robotics-premise: mechanical hazards always require human sign-off):
    4. an op the catalog declares `:escalates?` (today
       `:flag-mechanical-hazard`). The property lives in
       `meng.operation` rather than in a private set here, so the rule
       and the fact it applies cannot drift apart.
    5. low confidence (< `confidence-floor`)."
  (:require [meng.operation :as operation]
            [meng.store :as store]))

(def confidence-floor 0.6)

(defn- hard-violations [{:keys [proposal]} project-record]
  (cond-> []
    (nil? project-record)
    (conj {:rule :no-project :detail "未登録 project"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (not (operation/known? (:op proposal)))
    (conj {:rule :undeclared-operation
           :detail (str "未宣言の op: " (pr-str (:op proposal))
                        "（meng.operation/catalog に無い）")})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `meng.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [project-record (store/project store (:project-id request))
        hard (hard-violations {:proposal proposal} project-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        risky-op? (operation/escalating? (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
