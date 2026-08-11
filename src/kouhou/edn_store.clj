(ns kouhou.edn-store
  "EdnStore — the durable backend for `kouhou.store/Store`, canonical EDN in
  daily shards on disk.

  ## Why this exists

  Until this namespace, every live-ingest run built its store with
  `store/seed-db` — a `MemStore` over an atom. The actor fetched a real
  government press item, curated it, ran the PublicInfoGovernor over it,
  published it to app-aozora, wrote the briefing and the decision fact to the
  ledger… and then the process exited and took the whole ledger with it. The
  published record survived on someone else's PDS; **the actor's own audit
  trail did not survive at all**. `DatomicStore` was the declared answer but
  it is backed by `langchain.db`'s in-process EAVT, which is exactly as
  volatile unless it is pointed at a live pod. So the append-only ledger the
  README calls the publication provenance was, in production, a log that no
  one could read after the run.

  ## Shape on disk

      data/briefings/<YYYY-MM-DD>.edn   one canonical EDN map per line
      data/ledger/<YYYY-MM-DD>.edn      one canonical EDN map per line

  Line-delimited, append-only, sharded by UTC day. Append-only is the right
  discipline here and not a contradiction of the superproject's
  『文書は最新状態のみを表す』 rule: that rule governs *documents*, and names
  measurement/event series as the standing exception. A ledger of decisions
  IS the time series — rewriting a past decision destroys the thing it exists
  to prove. Briefings are appended for the same reason: the latest briefing
  per source is a fold over the shards, not a cell that gets overwritten, so
  『this source said X on the 3rd and Y on the 5th』 stays answerable.

  Daily shards rather than one growing file: a shard is immutable once its day
  closes, which is what makes the corpus content-addressable, and it bounds
  what a reader must parse to answer 『what happened today』.

  ## Consistency

  Reads are served from memory; the constructor loads every shard once. Writes
  append to the shard file (`:append true`) and then update memory, in that
  order — if the process dies between the two, the record is on disk and the
  next run picks it up. The reverse order would lose it. This is safe for one
  writer, which is the actor's own model: one live-ingest process per data
  directory. Two concurrent writers would interleave whole lines (the writes
  are single small appends, not the file rewrite the atom-based store did) but
  their in-memory `seq` counters would collide, so do not run two."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kouhou.canonical :as canon]
            [kouhou.store :as store])
  (:import (java.time ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def ^:private day-fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(defn utc-now
  "The default clock: current instant as an ISO-8601 UTC string, seconds
  precision. Injectable so tests get deterministic bytes — a store whose
  output depends on wall-clock cannot be asserted byte-for-byte."
  []
  (.format (ZonedDateTime/now ZoneOffset/UTC)
           DateTimeFormatter/ISO_INSTANT))

(defn day-of
  "The UTC date (YYYY-MM-DD) an ISO-8601 instant string belongs to. Parsed
  rather than sliced: an instant with an offset (`2026-08-11T00:30+09:00`)
  belongs to the previous UTC day, and slicing the first 10 characters would
  file it under the wrong one."
  [^String iso]
  (.format (.withZoneSameInstant (ZonedDateTime/parse iso) ZoneOffset/UTC) day-fmt))

(defn- shard-files
  "Every `<dir>/*.edn` shard, in name order — which is chronological order,
  because the names are ISO dates."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
           (sort-by #(.getName ^java.io.File %))))))

(defn read-shards
  "All records across every shard under `dir`, in chronological order."
  [dir]
  (into []
        (mapcat (fn [f] (canon/read-lines #(edn/read-string %) (slurp f))))
        (shard-files dir)))

(defn- append-line!
  "Append one canonical EDN line to `<dir>/<day>.edn`, creating the directory
  on first use."
  [dir day record]
  (let [f (io/file dir (str day ".edn"))]
    (io/make-parents f)
    (spit f (canon/canonical-str record) :append true)
    (.getPath f)))

(defn append!
  "Append `record` to the shard for its own `:kouhou/as-of` day. The shared
  primitive behind every plane in `data/` — the Store's two, and the corpus
  receipts `kouhou.run-live-ingest` writes — so all of them are one format
  with one reader, not three that drift apart."
  [dir record]
  (append-line! dir (day-of (:kouhou/as-of record)) record))

;; ── the Store ──────────────────────────────────────────────────────────────

(defrecord EdnStore [briefings-dir ledger-dir clock state]
  store/Store
  (briefing [_ id] (get-in @state [:briefings id]))
  (all-briefings [_] (sort-by :source-id (vals (:briefings @state))))
  (ledger [_] (:ledger @state))

  (commit-briefing! [s id payload]
    (let [as-of  (clock)
          record {:kouhou/kind :briefing
                  :kouhou/as-of as-of
                  :source-id   id
                  :payload     payload}]
      (append-line! briefings-dir (day-of as-of) record)
      (swap! state assoc-in [:briefings id] payload)
      s))

  (append-ledger! [_ fact]
    (let [as-of  (clock)
          record (assoc fact
                        :kouhou/kind :ledger
                        :kouhou/as-of as-of
                        :kouhou/seq (count (:ledger @state)))]
      (append-line! ledger-dir (day-of as-of) record)
      (swap! state update :ledger conj (dissoc record :kouhou/kind))
      fact)))

(defn- hydrate
  "Fold the shards back into the in-memory view the protocol serves reads
  from. Briefings fold last-write-wins per source-id (the shards are already
  chronological); ledger facts keep their recorded order."
  [briefings-dir ledger-dir]
  {:briefings (reduce (fn [m {:keys [source-id payload]}]
                        (assoc m source-id payload))
                      {}
                      (read-shards briefings-dir))
   :ledger    (mapv #(dissoc % :kouhou/kind) (read-shards ledger-dir))})

(defn edn-store
  "An `EdnStore` rooted at `data-dir` (default `data`), hydrated from whatever
  shards are already there — so a second run continues one ledger instead of
  starting a second one.

  Options: `:clock` (default `utc-now`), `:briefings-dir` / `:ledger-dir` to
  override the layout."
  ([] (edn-store "data" {}))
  ([data-dir] (edn-store data-dir {}))
  ([data-dir {:keys [clock briefings-dir ledger-dir]}]
   (let [bdir (or briefings-dir (str (io/file data-dir "briefings")))
         ldir (or ledger-dir    (str (io/file data-dir "ledger")))]
     (map->EdnStore {:briefings-dir bdir
                     :ledger-dir    ldir
                     :clock         (or clock utc-now)
                     :state         (atom (hydrate bdir ldir))}))))

(defn shard-paths
  "The shard files this store would read, for reporting what a run actually
  wrote. Reported rather than assumed: 『the run said it persisted』 and
  『the bytes are on disk』 are different claims."
  [{:keys [briefings-dir ledger-dir]}]
  {:briefings (mapv #(.getPath ^java.io.File %) (shard-files briefings-dir))
   :ledger    (mapv #(.getPath ^java.io.File %) (shard-files ledger-dir))})
