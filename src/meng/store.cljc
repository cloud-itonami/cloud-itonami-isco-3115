(ns meng.store
  "SSoT for the ISCO-08 3115 mechanical engineering technician field test
  and inspection actor. Store is a protocol injected into the `meng.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep backend; a
  Datomic/kotoba-server-backed implementation can be swapped in without
  touching the actor or governor (itonami actor pattern, per
  ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    project  — a registered mechanical site/installation (:project-id, :name, :location)
    record   — a committed test/inspection record under a project
               (mechanical test data, inspection log, site visit note) —
               written ONLY via commit-record!, never mutated in place
    ledger   — an append-only audit trail of every decision and of every
               act that followed one, regardless of outcome. Entries are
               built by `meng.ledger/append`, which types them and assigns
               their sequence, so a caller can neither append an untyped
               map nor choose its own position. This was a bare vector of
               whatever the graph handed it, and an escalation awaiting
               human sign-off appended nothing at all — `meng.ledger`'s
               docstring carries the measurement."
  (:require [meng.ledger :as led]))

(defprotocol Store
  (project [s project-id])
  (records-of [s project-id])
  (ledger [s])
  (register-project! [s project])
  (commit-record! [s record])
  (append-ledger! [s run-id type fact]
    "Append a typed `meng.ledger` entry for graph thread `run-id`."))

(defrecord MemStore [a]
  Store
  (project [_ project-id] (get-in @a [:projects project-id]))
  (records-of [_ project-id] (filter #(= project-id (:project-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-project! [s project]
    (swap! a assoc-in [:projects (:project-id project)] project) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s run-id type fact]
    (swap! a update :ledger #(led/append (or % []) run-id type fact)) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:projects {} :records [] :ledger []} seed)))))
