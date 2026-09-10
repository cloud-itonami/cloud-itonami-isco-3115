(ns meng.advisor
  "TestAdvisor — proposes a mechanical test, inspection, or hazard-logging
  operation (record test/measurement data, log inspection findings, flag mechanical
  safety hazards, schedule site visits) for a registered project. The advisor is
  swappable: `mock-advisor` (deterministic, default in dev/tests/CI) or
  `llm-advisor` (wraps a real `langchain.model/ChatModel`). Either way the advisor
  ONLY produces a PROPOSAL — it never writes to the store and has no notion of
  project provenance or mechanical-hazard risk; `meng.governor` is the independent
  system that decides whether the proposal may proceed, per the itonami actor pattern.

  A proposal is a map:
    {:op <a key of meng.operation/catalog>
     :effect :propose        ; the advisor NEVER emits a raw store write
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}
  The op vocabulary is read from `meng.operation`, not restated here —
  it used to be this prose list, and the prose bound nothing: an op no
  list mentioned committed at confidence 0.95, and `:op nil` threw an
  NPE inside `infer` before the governor ever saw it.

  An undeclared op now degrades to `:confidence 0.0` instead of
  crashing, so it reaches `meng.governor` and is held there. That is
  deliberate: the advisor stays a proposer and the governor stays the
  single gate. LLM parse failures likewise always yield
  `:confidence 0.0` — never fabricated confidence."
  (:require [meng.operation :as operation]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared op/stake
  straight through (a stand-in for what an LLM would extract from free
  text), with a stake-derived confidence.

  An op `meng.operation` does not declare yields confidence 0.0 and a
  named rationale rather than an exception — the governor, not this
  function, is the thing that refuses it."
  [_store {:keys [op stake] :as request}]
  (if (operation/known? op)
    {:op op
     :effect :propose
     :stake (or stake :low)
     :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
     :rationale (str "proposed " (name op) " for project " (:project-id request))}
    {:op op
     :effect :propose
     :stake :high
     :confidence 0.0
     :rationale (str "undeclared operation " (pr-str op)
                     " for project " (:project-id request))}))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a mechanical engineering technician advisor. Given a mechanical
   test, inspection, or hazard-reporting operation request, propose an :op,
   an honest :confidence (0.0-1.0), and a :stake (:low/:medium/:high).
   Never fabricate confidence you don't have.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "mechanical operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
