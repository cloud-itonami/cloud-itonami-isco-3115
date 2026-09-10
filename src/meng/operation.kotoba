(ns meng.operation
  "The declared vocabulary of operations the ISCO-08 3115 mechanical
  engineering technician actor may perform (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section).

  Before this namespace the vocabulary existed in three places and was
  binding in none of them: `meng.advisor`'s docstring listed four ops as
  prose, `meng.governor` kept a private `escalating-ops` set naming one
  of them, and the store accepted whatever `:op` keyword arrived.
  Measured on the tree this namespace was added to:

      (run-request! g {:project-id \"p1\" :op :decommission-the-plant
                       :stake :low} {} \"t\")
      ;; => :done, one committed record, confidence 0.95

  An operation nobody ever declared committed at the mock advisor's
  top confidence, because no rule in the governor asked whether the op
  meant anything. `:op nil` did not even reach the governor — it threw
  an NPE inside the advisor's `(name op)`.

  So the catalog is the single declaration and it is load-bearing:

    - `meng.governor` hard-holds an op that is not in it. An operation
      whose meaning is undeclared cannot be assessed, and cannot be put
      in front of a human as \"approve this?\" either — there is nothing
      to approve. It is a HOLD, not an escalation.
    - `meng.advisor` reads it instead of restating it in prose, and
      degrades an undeclared op to confidence 0.0 rather than crashing,
      so the governor stays the single gate rather than the advisor
      becoming a second one.
    - `:escalates?` lives here rather than in the governor because
      \"raising a mechanical hazard needs human sign-off\" is a property
      of the operation, declared once, not a copy kept beside the rule
      that applies it.")

(def catalog
  "op -> declaration. `:escalates?` true means the operation ALWAYS
  requires human sign-off regardless of advisor confidence, per the
  README's robotics premise."
  {:draft-test-record
   {:label       "record mechanical test / measurement data"
    :description "Draft a test record (readings, tolerances, pass/fail) against a registered project."
    :escalates?  false}

   :log-inspection-data
   {:label       "log inspection findings"
    :description "Record the findings of a scheduled or ad-hoc inspection against a registered project."
    :escalates?  false}

   :flag-mechanical-hazard
   {:label       "raise a mechanical safety hazard"
    :description "Raise a mechanical hazard found on site. Always requires human sign-off."
    :escalates?  true}

   :schedule-site-visit
   {:label       "schedule a site visit"
    :description "Put a site visit on the inspection schedule for a registered project."
    :escalates?  false}})

(def ops
  "Every declared op, sorted so reports and diffs are stable."
  (into (sorted-set) (keys catalog)))

(defn known?
  "Is `op` a declared operation? `nil` and every undeclared keyword are
  false — the reason `meng.advisor` can no longer be handed something
  it will crash on."
  [op]
  (contains? catalog op))

(defn escalating?
  "Does `op` always require human sign-off? False for an undeclared op:
  an undeclared op is held, not escalated, so answering true here would
  route it to a human who has nothing to approve."
  [op]
  (boolean (:escalates? (get catalog op))))

(defn describe
  "Operator-facing one-liner for `op`, or an explicit undeclared marker.
  Never returns nil — a report that silently omits the op it could not
  name reads as a report about three ops rather than four."
  [op]
  (if-let [{:keys [label]} (get catalog op)]
    (str op " — " label)
    (str op " — UNDECLARED (not in meng.operation/catalog)")))

(defn undeclared
  "The ops in `candidates` this catalog never declared. A set, so the
  caller can report which ones rather than only that there were some."
  [candidates]
  (into (sorted-set) (remove known?) candidates))
