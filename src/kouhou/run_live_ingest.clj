(ns kouhou.run-live-ingest
  "Non-interactive live-ingest entrypoint — the real-registry-fetch gap this
  namespace closes (README.md's own R0 status line used to read '... real
  registry fetch + real aozora Publisher wired at deploy'; the aozora
  Publisher half was already real via `kouhou.aozora`/`kouhou.deploy`, this
  closes the registry-fetch half). Mirrors kawaraban's already-landed
  `kawaraban.run-live-ingest` shape (ADR-2607110200) and safety engineering:
  the `KOUHOU_ALLOW_LIVE_INGEST` gate (checked inside `live-fetch/fetch-source!`,
  this script does not set it — an operator/workflow env config does), a
  spaced-out inter-source delay (same order of magnitude as kawaraban's
  measured aozora-graph read-latency mitigation, ADR-2607110200 addendum 2:
  a burst of writes there pushed shared PDS read latency from ~4s to ~50s),
  and per-source error isolation (`run-source!` never lets one source's HTTP
  failure or exception abort the rest of the run).

  Aggregate-first, ONE run = ONE briefing PER SOURCE (kouhou's own doctrine —
  README.md 'Invariant' + 'StateGraph' sections, docs/adr/0001-architecture.md
  §3, CLAUDE.md): `live-fetch/fetch-source!` already reduces each source's
  feed down to its single most-recent item before it ever reaches the actor,
  so this entrypoint runs exactly one full `kouhou.operation` StateGraph
  execution (:advise -> :govern -> :decide -> :commit|:hold) per source per
  invocation — never one execution per feed item. It is NOT a flood-publisher
  just because it fetches multiple sources; each source still only ever
  contributes at most one briefing.

  Uses the framework DEFAULT `mock-advisor` (deterministic faithful-excerpt
  summarizer, see `kouhou.advisor`) — the SAME advisor `kouhou.operation/build`
  already defaults to when no `:advisor` is given, so a live run's curation
  behavior is not a new code path, only its INPUT (a real fetched item
  instead of a hand-written fixture) is new. A real-LLM organizer already
  exists and is proven live (`kouhou.deploy/llm-advisor` against Murakumo,
  ADR-2607173100's `murakumo-main` alias applies there) — swapping it in here
  is a separate, deliberately out-of-scope seam change (`op/build`'s
  `:advisor` opt), not exercised by this entrypoint by default.

  Every governor HOLD is reported honestly, never worked around — a HELD
  source's briefing is simply not published; that is the PublicInfoGovernor
  functioning correctly, not a bug in this script.

  Usage:  KOUHOU_ALLOW_LIVE_INGEST=1 clojure -M:live-ingest
  Env:    KOUHOU_ALLOW_LIVE_INGEST  the live-fetch gate (kouhou.live-fetch)
          KOUHOU_REGISTRY_PATH      default \"registry/sources.seed.json\"
          KOUHOU_PDS                default kouhou.aozora/default-pds
          KOUHOU_IDENTITY_PATH      default \".kouhou/identity.edn\""
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [kouhou.aozora :as aozora]
            [kouhou.cacao :as cacao]
            [kouhou.edn-store :as edn-store]
            [kouhou.live-fetch :as live-fetch]
            [kouhou.operation :as op]
            [kouhou.publisher :as publisher]
            [kouhou.raw-archive :as raw]
            [kouhou.store :as store])
  (:gen-class))

(def ^:private inter-source-delay-ms
  "Spaced out, not fired back-to-back — kawaraban's own live-ingest run
  (ADR-2607110200 addendum 2) measured a single outlet's write burst alone
  pushing the shared aozora operator graph's read latency from ~4s to ~50s;
  this reuses the same order of magnitude to avoid concentrating kouhou's own
  writes (up to one createRecord per verified source) into one burst."
  3000)

(defn- pds []
  (or (System/getenv "KOUHOU_PDS") aozora/default-pds))

(defn- identity-path []
  (or (System/getenv "KOUHOU_IDENTITY_PATH") ".kouhou/identity.edn"))

(defn- default-data-dir [] (or (System/getenv "KOUHOU_DATA_DIR") "data"))
(defn- default-raw-dir  [] (or (System/getenv "KOUHOU_RAW_DIR")  "raw"))

(defn publish-allowed?
  "`KOUHOU_PUBLISH=0` runs ingest-and-store WITHOUT publishing to app-aozora.

  Default is publish, so an operator who was already running this gets the
  same behavior. The off switch exists because the two capabilities have
  different risk: fetching a registered government feed and writing it to our
  own disk is read-only and reversible, publishing to a shared PDS is neither.
  The superproject's observatory registry keeps kouhou's live alias out of the
  unattended hourly run for exactly that reason — `KOUHOU_PUBLISH=0` is the
  configuration that makes an unattended corpus run possible without also
  making it an unattended broadcaster."
  []
  (not= "0" (System/getenv "KOUHOU_PUBLISH")))

(defn real-publisher
  "kouhou.aozora's real app-aozora Publisher, bound to the actor's own
  self-sovereign identity (fresh-minted on first run if `.kouhou/identity.edn`
  does not yet exist locally — `cacao/load-or-create-identity!`)."
  [identity]
  (aozora/aozora-publisher {:pds        (pds)
                            :identity   identity
                            :json-write json/write-str
                            :json-read  json/read-str}))

(defn run-source!
  "One source's fetch -> `kouhou.operation` actor graph run. Returns a result
  map; NEVER throws — any exception (fetch, parse, or graph-node failure) is
  caught and reported as `:error`, so a caller iterating over many sources is
  never aborted by a single bad one.

  `fetch-fn`/`allowed?` are injectable (default the real HTTP GET + the real
  `KOUHOU_ALLOW_LIVE_INGEST` env check) so tests can exercise this fn with
  zero network I/O, the same seam `kouhou.live-fetch/fetch-source!` itself
  exposes.

  `opts` (6-arity) turns on persistence: `{:raw-dir … :corpus-dir … :clock …}`
  archives the fetched feed and appends a corpus receipt. Omitted (or nil) it
  behaves exactly as before, which is what keeps the offline suite free of
  disk I/O."
  ([source hosts actor] (run-source! source hosts actor live-fetch/jvm-http-get (live-fetch/live-allowed?)))
  ([source hosts actor fetch-fn allowed?] (run-source! source hosts actor fetch-fn allowed? nil))
  ([source hosts actor fetch-fn allowed? opts]
   (try
    (let [{:keys [refused reason item fetch-error raw-feed item-count]}
          (live-fetch/fetch-source! source hosts fetch-fn allowed?)
          sid   (:source-id source)
          clock (or (:clock opts) edn-store/utc-now)
          as-of (clock)
          ;; Archive BEFORE the actor runs. A feed that parsed to zero items,
          ;; or one whose briefing the governor then holds, is exactly the
          ;; case worth being able to look at later — archiving only what got
          ;; published would keep the evidence for the decisions that need it
          ;; least.
          receipt (when (and (:raw-dir opts) raw-feed)
                    (raw/save! (:raw-dir opts) (edn-store/day-of as-of) sid raw-feed))
          record! (fn [m]
                    (when (:corpus-dir opts)
                      (edn-store/append!
                       (:corpus-dir opts)
                       (merge {:kouhou/kind  :corpus
                               :kouhou/as-of as-of
                               :source-id    sid
                               :feed-url     (:url source)
                               :item-count   item-count
                               :raw          receipt}
                              m)))
                    m)]
      (cond
        refused
        {:source-id sid :refused true :reason reason}

        fetch-error
        (do (record! {:fetch-error fetch-error})
            {:source-id sid :fetch-error fetch-error})

        (nil? item)
        (do (record! {:fetch-error "fetch-source! returned no item and no fetch-error (unexpected)"})
            {:source-id sid :fetch-error "fetch-source! returned no item and no fetch-error (unexpected)"})

        :else
        (let [req {:op :source/digest :source-id sid :url (:url item)
                   :title (:title item) :raw (:raw item)}
              r   (g/run* actor
                          {:request req :context {:actor-id "kouhou" :phase 1 :registry hosts}}
                          {:thread-id sid})
              disp (get-in r [:state :disposition])]
          (record! {:disposition disp
                    :published   (get-in r [:state :published])
                    :item-url    (:url item)
                    :item-title  (:title item)})
          {:source-id  sid
           :disposition disp
           :published?  (boolean (get-in r [:state :published]))
           :pub         (get-in r [:state :published])
           :raw         receipt
           :item-url    (:url item)
           :item-title  (:title item)})))
    (catch Exception e
      {:source-id (:source-id source) :error (.getMessage e)}))))

(defn run-all!
  "One live-ingest pass over every `:verified` source in the registry at
  `registry-path`. `on-result` (default no-op) is called with each source's
  result map as soon as it finishes — visible progress per source rather than
  one buffered println at the end, same reasoning as kawaraban's
  `run-all!` (ADR-2607110200 addendum 2: a silent, fully-buffered run is
  indistinguishable from a hang from the outside)."
  ([registry-path] (run-all! registry-path (fn [_])))
  ([registry-path on-result] (run-all! registry-path on-result {}))
  ([registry-path on-result {:keys [data-dir raw-dir publish? limit]}]
   (let [data-dir (or data-dir (default-data-dir))
         raw-dir  (or raw-dir (default-raw-dir))
         publish? (if (some? publish?) publish? (publish-allowed?))
         registry (live-fetch/load-registry registry-path json/read-str)
         hosts    (live-fetch/registry->host-set registry)
         sources  (cond->> (live-fetch/verified-sources registry)
                    limit (take limit))
         ;; Identity is minted/loaded even when not publishing: it names the
         ;; actor in its own ledger, and a corpus whose facts cannot say who
         ;; recorded them is worth less than one that can.
         id       (cacao/load-or-create-identity! (identity-path))
         pub      (if publish? (real-publisher id) (publisher/mock-publisher))
         ;; The durable store. Was `store/seed-db` — an atom that took the
         ;; whole ledger with it when the process exited.
         s        (edn-store/edn-store data-dir)
         actor    (op/build s {:publisher pub})
         opts     {:raw-dir raw-dir :corpus-dir (str (io/file data-dir "corpus"))}]
     {:identity id
      :publish? publish?
      :store    s
      :data-dir data-dir
      :raw-dir  raw-dir
      :results
      (mapv (fn [source]
              (when (pos? inter-source-delay-ms) (Thread/sleep inter-source-delay-ms))
              (let [result (run-source! source hosts actor live-fetch/jvm-http-get
                                        (live-fetch/live-allowed?) opts)]
                (on-result result)
                result))
            sources)})))

(defn -main [& _]
  (let [registry-path (or (System/getenv "KOUHOU_REGISTRY_PATH") "registry/sources.seed.json")
        limit         (some-> (System/getenv "KOUHOU_MAX_SOURCES") Long/parseLong)
        {:keys [identity results publish? store data-dir raw-dir]}
        (run-all! registry-path (fn [r] (println (pr-str r)) (flush)) {:limit limit})
        committed (filter #(= :commit (:disposition %)) results)
        held      (filter #(= :hold (:disposition %)) results)
        published (filter :published? results)
        archived  (filter :raw results)
        errors    (filter #(or (:error %) (:fetch-error %)) results)]
    (println "=== kouhou live-ingest ===")
    (println "actor did:key:" (:did identity))
    (println (str (count results) " sources, "
                   (count committed) " committed, "
                   (count held) " held, "
                   (count published) " published, "
                   (count errors) " with errors"))
    (when-not publish?
      (println "publish: OFF (KOUHOU_PUBLISH=0) — ingest + store only, nothing sent to app-aozora"))
    ;; Report what is on disk, not what was intended to be. The shard paths
    ;; and the ledger depth come from the store itself after the run.
    (println (str "persisted: " (count archived) " feeds under " raw-dir
                  ", ledger now " (count (store/ledger store)) " facts in " data-dir))
    (doseq [[plane paths] (edn-store/shard-paths store)]
      (println (str "  " (name plane) ": " (str/join " " paths))))
    (when (and (seq errors) (= (count errors) (count results)))
      ;; every single source errored -- likely a systemic problem (network,
      ;; gate, identity), not per-source flakiness -- fail the run loudly
      ;; instead of a silent green no-op
      (System/exit 1))))
