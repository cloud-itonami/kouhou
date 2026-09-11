(ns kouhou.operation
  "OperationActor — one source digest = one supervised actor run, expressed as
  a langgraph-clj StateGraph. organizer (the contained intelligence node) is
  sealed into :advise; its proposal is ALWAYS routed through the
  PublicInfoGovernor (:govern) before anything commits to the SSoT or
  publishes to app-aozora. Mirrors the containment + independent-governor +
  append-only-ledger topology (tashikame.operation / tsumugu.operation).

  Everything the actor depends on is injected (each a swap, not a rewrite):
    - the Store     (MemStore | DatomicStore | kotoba-server)  — `store` arg
    - the Advisor   (mock organizer | real-LLM on Murakumo)    — :advisor opt
    - the Publisher (Mock | real app-aozora createRecord)      — :publisher opt
    - the Phase     (0 observe → 1 autonomous-publish)         — :phase in ctx
    - the Registry  (public-sector host whitelist)             — :registry in ctx

  One run = intake → advise → govern → decide → commit | hold. NO unbounded
  inner loop; NO interrupt-before — publication is autonomous by default
  (ADR-2606281500). The PublicInfoGovernor's HARD violations are the only thing
  that withholds publication. Aggregate-first: one run publishes ONE briefing."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [kouhou.advisor :as advisor]
            [kouhou.governor :as governor]
            [kouhou.phase :as phase]
            [kouhou.publisher :as publisher]
            [kouhou.store :as store]))

(defn- post-body [b]
  (str "【広報】" (:title b) " — " (:summary b)
       (when (:source-url b) (str " （出典: " (:source-url b) "）"))))

(defn- briefing-record [request context b]
  {:source-id  (:source-id request)
   ;; deterministic per-source rkey (kouhou.aozora defaults to "self" when
   ;; absent) — without this, publishing briefings for MULTIPLE sources in one
   ;; live-ingest invocation would all createRecord at the same rkey "self" in
   ;; the same collection and silently overwrite each other. One source ==
   ;; one stable record slot; a later commit for the same source-id updates
   ;; its own record rather than colliding with any other source's.
   :rkey       (:source-id request)
   :actor      (:actor-id context)
   :title      (:title b)
   :summary    (:summary b)
   :source-url (:source-url b)
   :domain     (:domain b)
   :tags       (:tags b)
   :collection publisher/collection
   :text       (post-body b)})

(defn build
  "Compiles the kouhou OperationActor graph bound to `store`. opts:
    :advisor      — a `kouhou.advisor/Advisor` (default: mock-advisor)
    :publisher    — a `kouhou.publisher/Publisher` (default: mock-publisher)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor publisher checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    publisher    (publisher/mock-publisher)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; injected actor-id / phase / registry
         :proposal    {:default nil}
         :verdict     {:default nil}   ; PublicInfoGovernor result
         :disposition {:default nil}   ; :commit | :hold
         :record      {:default nil}   ; the briefing to commit/publish
         :published   {:default nil}   ; {:uri :cid} when published
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; organizer (contained intelligence) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-organize advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; PublicInfoGovernor — independent censor (separate system than organizer).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal)}))

      ;; Decide: HARD violation → :hold; else :commit (autonomous publish).
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (case (governor/verdict->disposition verdict)
            :hold
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}
            :commit
            {:disposition :commit
             :record (assoc (briefing-record request context (first (:briefings proposal)))
                            :warnings (:warnings verdict))})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger, and (when
      ;; the phase allows) publishes to app-aozora. Autonomous by default.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (let [ph       (:phase context phase/default-phase)
                publish? (and (phase/publish-allowed? ph)
                              (= :assessment (:effect proposal)))
                pub      (when publish? (publisher/publish! publisher record))
                f        {:t           :committed
                          :op          (:op request)
                          :actor       (:actor-id context)
                          :source      (:source-id request)
                          :disposition :commit
                          :published?  publish?
                          :pub         pub
                          :warnings    (:warnings record)
                          :briefings   (:briefings proposal)}]
            (store/commit-briefing! store (:source-id request) (dissoc record :warnings))
            (store/append-ledger! store f)
            {:published pub :audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation, no publish.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(= :governor-hold (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit :hold)))
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph {:checkpointer checkpointer})))
