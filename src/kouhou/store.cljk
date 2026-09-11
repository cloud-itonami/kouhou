(ns kouhou.store
  "SSoT for the kouhou (広報 / public-info curation) actor — the append-only
  briefing ledger behind a `Store` protocol so the backend is a swap, not a
  rewrite (MemStore default ‖ DatomicStore via langchain.db, itself swappable
  to real Datomic Local / kotoba-server pod e.g. kotobase.net).

  Domain: a fetched source item (url + title + raw text, from a REGISTERED
  public-sector / public-interest source) → a curated briefing (faithful
  summary + provenance URL + domain/tags) → a published social post on
  app-aozora (collection com.etzhayyim.apps.kouhou.publicBriefing). The
  append-only ledger is the publication provenance — every briefing is an
  immutable fact, never overwritten; a held (PublicInfoGovernor-rejected)
  briefing is recorded as a hold, never published.

  The store talks to its backend ONLY through the langchain.db `:db-api` map
  {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process EAVT) and
  `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC) both implement it, so
  the same `DatomicStore` record runs on either by construction."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (briefing [s id] "the committed briefing record for a source-id, or nil")
  (all-briefings [s])
  (ledger [s])
  (commit-briefing! [s id payload] "commit one curated briefing record")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (briefing [_ id] (get-in @a [:briefings id]))
  (all-briefings [_] (sort-by :source-id (vals (:briefings @a))))
  (ledger [_] (:ledger @a))
  (commit-briefing! [s id payload] (swap! a assoc-in [:briefings id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore."
  []
  (->MemStore (atom {:briefings {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────

(def ^:private schema
  {:kouhou.briefing/id {:db/unique :db.unique/identity}
   :kouhou.ledger/seq  {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (briefing [this id]
    (dec* (q* this '[:find ?p . :in $ ?id :where
                     [?e :kouhou.briefing/id ?id]
                     [?e :kouhou.briefing/payload ?p]]
               id)))
  (all-briefings [this]
    (->> (q* this '[:find [?id ...] :where [?e :kouhou.briefing/id ?id]])
         (map #(briefing this %)) (sort-by :source-id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where
                    [?e :kouhou.ledger/seq ?s] [?e :kouhou.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-briefing! [s id payload]
    (tx* s [{:kouhou.briefing/id id :kouhou.briefing/payload (enc payload)}]) s)
  (append-ledger! [s fact]
    (tx* s [{:kouhou.ledger/seq (count (ledger s)) :kouhou.ledger/fact (enc fact)}]) fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (verifiable
  offline, no network). For the kotoba-server pod (kotobase.net), bind the
  same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see docs/adr/0001-architecture.md)."
  []
  (->DatomicStore d/api (d/create-conn schema)))
