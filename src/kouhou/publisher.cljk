(ns kouhou.publisher
  "Publisher — the outbound surface for a kouhou briefing, injected so the
  network is a swap (MockPublisher default ‖ real app-aozora createRecord via
  `kouhou.aozora`). The graph never reaches the network directly; :commit
  calls `(publish! publisher record)` only after the PublicInfoGovernor passed
  AND the phase allows publication (autonomous by default, ADR-2606281500).

  record shape (what gets published):
    {:source-id :title :summary :source-url :domain :tags :text (social-post body)
     :collection \"com.etzhayyim.apps.kouhou.publicBriefing\"}")

(def collection "com.etzhayyim.apps.kouhou.publicBriefing")

(defprotocol Publisher
  (publish! [p record] "publish one briefing record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/kouhou/" (:source-id record))
     :cid (str "mock:" (:source-id record))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been published."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
