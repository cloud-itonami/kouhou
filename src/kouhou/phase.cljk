(ns kouhou.phase
  "Phase 0→1 staged rollout for kouhou. Per ADR-2606281500 (種をまく),
  publication is AUTONOMOUS by default — there is NO 'every publish needs
  approval' phase (that would be per-post prior restraint, which the doctrine
  lifts). The phase only decides whether a governor-clean briefing is PUBLISHED
  or SHADOW-RECORDED; it can only ever withhold publication, never force it.

    Phase 0  observe            — governor-clean briefings recorded to the ledger
                                   but NOT published (shadow / observe).
    Phase 1  autonomous-publish — governor-clean briefings publish autonomously
                                   (DEFAULT — 種をまく).")

(def phases
  {0 {:label "observe"            :publish? false}
   1 {:label "autonomous-publish" :publish? true}})

(def default-phase 1)

(defn publish-allowed? [phase]
  (:publish? (get phases phase (get phases default-phase))))
