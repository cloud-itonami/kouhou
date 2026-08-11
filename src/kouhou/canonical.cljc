(ns kouhou.canonical
  "Canonical EDN serialization — one value, one byte string, forever.

  The briefing shards and the ledger are the actor's source of truth (the
  superproject's `agent loop の正本は Git + EDN + DataLad` rule), so their
  bytes are read by three things that do not agree unless the encoding is
  stable: `git diff` (review), sha256 (the receipt that links a shard to the
  annexed raw feed it was derived from), and a future projection loader.

  Clojure's `pr-str` does not give that. Map key order follows insertion for
  small maps and hash order once a map grows past 8 entries, so the SAME value
  can print two ways in one process — a diff of reordered lines, and a
  different digest for identical content. `canonical-str` sorts every map by
  the printed form of its key, recursively, which is total (keywords, strings,
  numbers and symbols all compare) where `compare` on mixed key types throws."
  (:require [clojure.string :as str]))

(defn- key-order
  "Total order over map keys: compare their printed forms. `compare` alone
  would throw on a map that mixes keyword and string keys — which the ledger
  facts do, since publisher responses carry string keys from JSON."
  [a b]
  (compare (pr-str a) (pr-str b)))

(defn canonical
  "`v` with every map replaced by a key-sorted map, recursively. Vectors,
  lists and sets keep their own order — a set's print order is already
  unstable, so callers that need a stable set must hand over a vector."
  [v]
  (cond
    (map? v)        (into (sorted-map-by key-order)
                          (map (fn [[k x]] [k (canonical x)])) v)
    (vector? v)     (mapv canonical v)
    (set? v)        (set (map canonical v))
    (sequential? v) (mapv canonical v)
    :else           v))

(defn canonical-str
  "One line of canonical EDN for `v`, newline included. No newline can appear
  inside the line: the shards are line-delimited, so an embedded newline would
  split one record into two unreadable halves. `pr-str` escapes newlines inside
  strings, and nothing else in an EDN value can produce a bare one."
  [v]
  (str (pr-str (canonical v)) "\n"))

(defn read-lines
  "Parse a line-delimited canonical-EDN blob into a vector of values. Blank
  lines are skipped; a malformed line is NOT skipped — it throws, because a
  silently dropped record in an append-only ledger is indistinguishable from a
  record that was never written."
  [read-string-fn ^String blob]
  (into []
        (comp (map str/trim)
              (remove str/blank?)
              (map read-string-fn))
        (str/split-lines blob)))
