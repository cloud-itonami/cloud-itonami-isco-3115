(ns meng.ledger
  "The append-only audit trail for the ISCO-08 3115 mechanical
  engineering technician actor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section).

  `meng.store`'s docstring called the ledger \"an append-only audit trail
  of every proposal/verdict/disposition, regardless of outcome\". Measured
  on the tree this namespace was added to, it was none of those things:

      A  ordinary commit          -> [{:disposition :commit  :record {..}}]
      B  hazard escalated,
         never signed off         -> []                     ; <-- nothing
      C  hazard escalated,
         human signed off         -> [{:disposition :commit  :record {..}}]

  Two holes, both of the shape CLAUDE.md names as `「飛ばした」と
  「合格した」が出力で区別できるか`:

    B  A mechanical hazard was raised, the graph interrupted for human
       sign-off, and the trail is EMPTY. A hazard awaiting sign-off is
       byte-identical to a run that never happened. For an actor whose
       stated contract is that hazards may not be escalated \"without
       governor approval and audit evidence\", the pending escalation —
       the single state an auditor most needs to see — was the one state
       that left no evidence.

    C  A record committed after human sign-off is byte-identical to one
       committed with no human involved. The sign-off cannot be proved,
       and its absence cannot be proved either.

  So entries are typed and every phase that decides or acts writes one.
  A run is a `:run-id` (the graph's thread-id), and `runs` folds the
  trail back into per-run outcomes, including `:awaiting-approval` —
  the state that used to be invisible.

  Counts, not booleans, throughout: this fleet has been bitten by
  self-checks that could only say \"something was wrong\" and so could
  not tell one unresolved escalation from a wholly unrecorded actor.")

(def entry-types
  "Every kind of entry the graph may append. Anything else in a trail is
  reported by `audit` as `:unknown-entry-type` rather than ignored."
  #{:decided :approved :committed :held})

(def terminal-types
  "Entry types that end a run."
  #{:committed :held})

(defn entry
  "Build a typed ledger entry. `seq-no` is the entry's position in the
  trail; `run-id` is the graph thread the entry belongs to."
  [seq-no run-id type fact]
  {:ledger/seq    seq-no
   :ledger/run-id run-id
   :ledger/type   type
   :ledger/fact   fact})

(defn append
  "Append a typed entry for `run-id` to `entries` (a vector, possibly
  empty). Pure — returns the new vector. `:ledger/seq` is assigned from
  the trail's own length, so a caller cannot pick its own sequence."
  [entries run-id type fact]
  (let [entries (vec entries)]
    (conj entries (entry (count entries) run-id type fact))))

(defn of-run
  "Every entry belonging to `run-id`, in trail order."
  [entries run-id]
  (filterv #(= run-id (:ledger/run-id %)) entries))

(defn- run-state
  "Fold one run's entries into its outcome. `:awaiting-approval` is the
  case the old bare-vector trail could not express at all."
  [run-entries]
  (let [types     (into #{} (map :ledger/type) run-entries)
        decided   (some #(when (= :decided (:ledger/type %)) %) run-entries)
        route     (get-in decided [:ledger/fact :disposition])]
    (cond
      (contains? types :committed)
      (if (contains? types :approved) :approved-committed :committed)

      (contains? types :held) :held

      (= :request-approval route) :awaiting-approval

      (some? decided) :decided-not-acted

      :else :no-decision)))

(defn runs
  "Fold the whole trail into `run-id -> {:state .. :entries n :ops #{}}`.
  `:state` is one of

    :committed           committed with no human in the loop
    :approved-committed  escalated, signed off, then committed
    :held                refused by a hard invariant
    :awaiting-approval   escalated and NOT yet signed off  <-- was invisible
    :decided-not-acted   a disposition was chosen and nothing followed it
    :no-decision         entries exist for the run but none records a decision"
  [entries]
  (reduce (fn [acc [run-id run-entries]]
            (assoc acc run-id
                   {:state   (run-state run-entries)
                    :entries (count run-entries)
                    :ops     (into (sorted-set)
                                   (keep #(get-in % [:ledger/fact :op]))
                                   run-entries)}))
          {}
          (group-by :ledger/run-id entries)))

(defn unresolved
  "Run ids that are escalated and still awaiting human sign-off. The
  operator-facing answer to \"what is waiting on me\", and the reason
  this namespace exists: before it, this set could not be computed
  because the escalation wrote nothing."
  [entries]
  (into (sorted-set)
        (keep (fn [[run-id {:keys [state]}]]
                (when (= :awaiting-approval state) run-id)))
        (runs entries)))

(defn audit
  "Check the trail's own structure. Returns
  `{:count n :runs n :problems [..] :problem-count n :ok? bool}`.

  Problems reported:

    :seq-out-of-order    entry i does not carry seq i (reorder / gap)
    :unknown-entry-type  a type outside `entry-types`
    :missing-run-id      an entry that belongs to no run
    :commit-not-decided  a committed record with no `:decided` before it —
                         a write that no disposition authorised
    :approved-not-escalated
                         an `:approved` entry for a run whose decision was
                         not `:request-approval` — a sign-off on something
                         that never asked for one

  `:problem-count` rather than a boolean, so one stray entry is
  distinguishable from a wholly unrecorded actor."
  [entries]
  (let [entries (vec entries)
        structural
        (into []
              (mapcat
               (fn [i]
                 (let [{:ledger/keys [seq run-id type]} (nth entries i)]
                   (cond-> []
                     (not= i seq)
                     (conj {:kind :seq-out-of-order :at i :expected i :got seq})

                     (not (contains? entry-types type))
                     (conj {:kind :unknown-entry-type :at i :got type})

                     (nil? run-id)
                     (conj {:kind :missing-run-id :at i})))))
              (range (count entries)))
        per-run
        (into []
              (mapcat
               (fn [[run-id run-entries]]
                 (let [types   (into #{} (map :ledger/type) run-entries)
                       decided (some #(when (= :decided (:ledger/type %)) %) run-entries)
                       route   (get-in decided [:ledger/fact :disposition])]
                   (cond-> []
                     (and (contains? types :committed) (nil? decided))
                     (conj {:kind :commit-not-decided :run run-id})

                     (and (contains? types :approved)
                          (not= :request-approval route))
                     (conj {:kind :approved-not-escalated :run run-id :route route})))))
              (group-by :ledger/run-id entries))
        probs (into structural per-run)]
    {:count         (count entries)
     :runs          (count (group-by :ledger/run-id entries))
     :problems      probs
     :problem-count (count probs)
     :ok?           (zero? (count probs))}))
