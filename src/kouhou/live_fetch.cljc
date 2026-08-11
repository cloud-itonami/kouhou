(ns kouhou.live-fetch
  "live_fetch.cljc — kouhou 広報 R0->R1: real HTTP GET + RSS 2.0 / RSS 1.0 (RDF) /
  Atom 1.0 feed parsing for the public-sector/public-interest source registry
  (`registry/sources.seed.json`). ADR-2607197800 (world-scope registry) +
  this change (real fetch code, mirroring kawaraban's already-landed
  ADR-2607110200 live_fetch.cljc — same minimal, dependency-free, regex-based
  scanner, ported/adapted rather than reimplemented from scratch).

  Discipline: this namespace only fetches + parses a feed item into the SAME
  `{:url :title :raw :source-id}` request shape `kouhou.operation`'s StateGraph
  already consumes (see `kouhou.sim`), and `registered-source?` (from
  `kouhou.ingest`) is called UNCHANGED to gate which registry entries are
  even eligible to be fetched from. This namespace does NOT reimplement or
  weaken the PublicInfoGovernor's checks (`kouhou.governor/check` still runs,
  downstream, on every :advise proposal) — it only gets a real item onto the
  actor's doorstep instead of a hand-written fixture. Same 'inherit the gate,
  don't reinvent it' discipline as kawaraban (ADR-2607110200).

  Self-contained: a minimal, dependency-free RSS 2.0 / RSS 1.0 (RDF) / Atom 1.0
  scanner (regex-based, not a full XML parser — sufficient for the flat
  <item>/<entry> shape real government feeds use). gov-online.go.jp is RDF
  (dc:date instead of pubDate, same shape Deutsche Welle used for kawaraban),
  most others are RSS 2.0, gov.uk is Atom.

  The live gate is `KOUHOU_ALLOW_LIVE_INGEST` (mirrors kawaraban's
  `KAWARABAN_ALLOW_LIVE_INGEST`, ADR-2607110200) — actually issuing the HTTP
  GET is refused unless this env var is set. Parsing text you already have is
  not a live fetch, so the pure parsing fns below have no gate; only
  `fetch-source!` (the #?(:clj) HTTP edge) checks it."
  (:require [clojure.string :as str]
            [kouhou.ingest :as ingest]))

;; ── live-ingest gate (KOUHOU_ALLOW_LIVE_INGEST) ─────────────────────────────

(defn live-allowed?
  "Is real network fetch permitted? Mirrors kawaraban's
  `KAWARABAN_ALLOW_LIVE_INGEST` gate — default OFF, an operator/workflow env
  config turns it on. Injectable via `getenv-fn` for tests (default
  `#?(:clj System/getenv :cljs (constantly nil))`, so this stays portable and
  the test suite never has to mutate real process env)."
  ([] (live-allowed? #?(:clj #(System/getenv %) :cljs (fn [_] nil))))
  ([getenv-fn] (= "1" (getenv-fn "KOUHOU_ALLOW_LIVE_INGEST"))))

;; ── XML entity / CDATA handling (ported from kawaraban.methods.live-fetch) ──

(defn unescape-xml
  "Decode the 5 predefined XML entities + numeric character references.
  Sufficient for RSS/Atom/RDF text nodes (no DTD-defined entities in
  practice)."
  [^String s]
  (if (nil? s)
    ""
    (-> s
        (str/replace #"&#x([0-9a-fA-F]+);" (fn [[_ hex]] (str (char (Integer/parseInt hex 16)))))
        (str/replace #"&#(\d+);" (fn [[_ dec]] (str (char (Integer/parseInt dec)))))
        (str/replace "&lt;" "<")
        (str/replace "&gt;" ">")
        (str/replace "&quot;" "\"")
        (str/replace "&apos;" "'")
        (str/replace "&amp;" "&"))))

(defn strip-cdata
  "Unwrap a `<![CDATA[ … ]]>`-wrapped text node, else pass through."
  [^String s]
  (if (nil? s)
    ""
    (let [s (str/trim s)]
      (if (and (str/starts-with? s "<![CDATA[") (str/ends-with? s "]]>"))
        (subs s 9 (- (count s) 3))
        s))))

(defn clean-text
  "strip-cdata then unescape-xml then trim — the standard text-node cleanup."
  [s]
  (-> s strip-cdata unescape-xml str/trim))

;; ── minimal block/tag scanner (regex-based, non-validating) ────────────────

(defn- blocks
  "All `<tag …>…</tag>` block bodies (non-greedy, DOTALL) for a given tag name."
  [^String xml tag]
  (mapv second (re-seq (re-pattern (str "(?s)<" tag "(?:\\s[^>]*)?>(.*?)</" tag ">")) xml)))

(defn- first-tag-text
  "First `<tag …>text</tag>` body inside `block`, cleaned. nil if absent."
  [^String block tag]
  (when-let [m (re-find (re-pattern (str "(?s)<" tag "(?:\\s[^>]*)?>(.*?)</" tag ">")) block)]
    (clean-text (second m))))

(defn- self-closing-attr
  "Value of `attr` on the first self-closing/opening `<tag … attr=\"v\" …>` in `block`."
  [^String block tag attr]
  (when-let [m (re-find (re-pattern (str "<" tag "\\s[^>]*" attr "=\"([^\"]*)\"")) block)]
    (unescape-xml (second m))))

(defn- atom-link-href
  "Atom `<link href=\"…\" rel=\"alternate\"?/>` — prefer rel=alternate, else the first link."
  [^String block]
  (or (when-let [m (re-find #"<link\s+[^>]*rel=\"alternate\"[^>]*href=\"([^\"]*)\"" block)]
        (unescape-xml (second m)))
      (when-let [m (re-find #"<link\s+[^>]*href=\"([^\"]*)\"[^>]*rel=\"alternate\"" block)]
        (unescape-xml (second m)))
      (self-closing-attr block "link" "href")))

;; ── RFC-822 / ISO-8601 pubDate → epoch seconds (best-effort, no timezone db) ─

(def ^:private months
  {"Jan" 1 "Feb" 2 "Mar" 3 "Apr" 4 "May" 5 "Jun" 6 "Jul" 7 "Aug" 8 "Sep" 9 "Oct" 10 "Nov" 11 "Dec" 12})

(defn- ldt->epoch
  "java.time.LocalDateTime (UTC-assumed) -> epoch seconds. JVM only."
  [y mo d hh mm ss]
  #?(:clj (.toEpochSecond (java.time.LocalDateTime/of (int y) (int mo) (int d) (int hh) (int mm) (int ss))
                          java.time.ZoneOffset/UTC)
     :cljs 0))

(defn parse-date->epoch
  "Best-effort epoch-seconds parse of an RFC-822 (`Mon, 02 Jan 2006 15:04:05 GMT`) or
  ISO-8601 (`2006-01-02T15:04:05Z`) date string. Returns 0 on anything unrecognized —
  never throws (a malformed date must not abort a fetch; it just sorts last)."
  [s]
  (or
   (when-let [[_ d mon y hh mm ss]
              (re-find #"(?:\w{3},\s*)?(\d{1,2})\s+(\w{3})\s+(\d{4})\s+(\d{2}):(\d{2}):(\d{2})" (or s ""))]
     (when-let [mo (get months mon)]
       (ldt->epoch (Integer/parseInt y) mo (Integer/parseInt d)
                   (Integer/parseInt hh) (Integer/parseInt mm) (Integer/parseInt ss))))
   (when-let [[_ y mo d hh mm ss]
              (re-find #"(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})" (or s ""))]
     (ldt->epoch (Integer/parseInt y) (Integer/parseInt mo) (Integer/parseInt d)
                 (Integer/parseInt hh) (Integer/parseInt mm) (Integer/parseInt ss)))
   0))

;; ── feed-item → kouhou request-item shape ───────────────────────────────────
;; kouhou's :source/digest request is `{:url :title :raw :source-id}`
;; (kouhou.operation / kouhou.sim) — much smaller than kawaraban's
;; ":news.article/*" wire shape, since kouhou's organizer produces the
;; provenance-checked summary, not a mirror record.

(defn rss-item->item
  "One RSS 2.0 (or RSS 1.0/RDF, same <item> shape) block -> a feed-item map
  {:title :url :raw :as-of}. Date falls back to Dublin Core `dc:date`
  (ISO-8601) when RSS 2.0's `pubDate` (RFC-822) is absent — RDF/RSS 1.0 feeds
  (e.g. 政府広報オンライン/gov-online.go.jp) use dc:date instead."
  [block]
  {:title (or (first-tag-text block "title") "")
   :url   (or (first-tag-text block "link") "")
   :raw   (or (first-tag-text block "description") "")
   :as-of (parse-date->epoch (or (first-tag-text block "pubDate") (first-tag-text block "dc:date")))})

(defn atom-entry->item
  "One Atom <entry> block -> a feed-item map {:title :url :raw :as-of}."
  [block]
  {:title (or (first-tag-text block "title") "")
   :url   (or (atom-link-href block) "")
   :raw   (or (first-tag-text block "summary") (first-tag-text block "content") "")
   :as-of (parse-date->epoch (or (first-tag-text block "published") (first-tag-text block "updated")))})

(defn parse-feed
  "Parse RSS 2.0, RSS 1.0/RDF, or Atom 1.0 XML text into a vector of feed-item
  maps ({:title :url :raw :as-of}), newest first. Auto-detects the feed kind
  by looking for `<rss` / `<rdf:RDF` vs `<feed`. Returns [] for anything
  unrecognized (never throws — a feed-format surprise is not a governor
  concern, it's just an empty result for that source)."
  [^String xml]
  (let [items (cond
                (re-find #"(?i)<rss[\s>]" xml)
                (mapv rss-item->item (blocks xml "item"))

                ;; RSS 1.0/RDF (e.g. 政府広報オンライン): <rdf:RDF> root, same flat
                ;; <item> shape as RSS 2.0.
                (re-find #"(?i)<rdf:RDF[\s>]" xml)
                (mapv rss-item->item (blocks xml "item"))

                (re-find #"(?i)<feed[\s>]" xml)
                (mapv atom-entry->item (blocks xml "entry"))

                :else [])]
    (vec (sort-by :as-of > items))))

(defn latest-item
  "The single most-recent item in `items` (by :as-of, descending — parse-feed
  already sorts this way, this is just `first` with an empty-safe nil).
  kouhou's doctrine is aggregate-first / one run = one source digest (its own
  CLAUDE.md + docs/adr/0001-architecture.md §3) — a live-ingest pass over a
  multi-item feed proposes exactly ONE briefing candidate per source, not one
  per item, so it never becomes a flood-publisher just because the feed has
  many items."
  [items]
  (first items))

;; ── the G-equivalent edge: actually fetching a URL is gated ─────────────────

#?(:clj
   (defn jvm-http-get
     "Default fetch-fn: JDK HttpClient GET (no dependency), 10s timeout, follows
     redirects (some feed URLs redirect http->https or to a canonical host)
     and sends a descriptive User-Agent (some government sites refuse
     bare/no-UA requests)."
     [^String url]
     (let [client (-> (java.net.http.HttpClient/newBuilder)
                      (.followRedirects java.net.http.HttpClient$Redirect/NORMAL)
                      (.build))
           req (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                   (.timeout (java.time.Duration/ofSeconds 10))
                   (.header "User-Agent" "kouhou/1.0 (+https://github.com/etzhayyim/com-etzhayyim-kouhou; public-interest info curator bot, faithful-summary-with-provenance only)")
                   (.GET)
                   (.build))
           resp (.send client req (java.net.http.HttpResponse$BodyHandlers/ofString))]
       (.body resp))))

(defn fetch-source!
  "The live-fetch edge: fetch `source`'s :url (a registry entry, see
  `registry/sources.seed.json` — :host/:url/:source-id) and parse it into the
  single latest feed item. Refuses (returns a refusal map, throws nothing)
  unless `(live-allowed?)` — same operator-gate semantics as kawaraban's
  `fetch-outlet!`. `registered-source?` (kouhou.ingest, UNCHANGED) still gates
  which host is even eligible — this fn will not fetch a source whose host
  isn't in `hosts` (a host SET, e.g. `(registry->host-set registry)`). A
  network-level failure (timeout / DNS / non-feed response) is caught and
  reported as `:fetch-error`, never thrown — one source's failure must not
  abort a caller iterating over many sources."
  ([source hosts] (fetch-source! source hosts #?(:clj jvm-http-get :cljs (fn [_] ""))))
  ([source hosts fetch-fn] (fetch-source! source hosts fetch-fn (live-allowed?)))
  ([source hosts fetch-fn allowed?]
   (cond
     (not allowed?)
     {:refused true
      :reason "live HTTP fetch is gated (KOUHOU_ALLOW_LIVE_INGEST). Set KOUHOU_ALLOW_LIVE_INGEST=1 to enable — founder/Council explicit go-live instruction required, ADR-2607110200 precedent."}

     (not (ingest/registered-source? hosts (:url source)))
     {:refused true
      :reason (str "source host not in the public-sector/public-interest registry: " (:url source))}

     :else
     (try
       (let [xml   (fetch-fn (:url source))
             items (parse-feed xml)
             item  (latest-item items)]
         ;; :raw-feed is the whole fetched document, carried out alongside the
         ;; one item the doctrine allows through. The actor still sees exactly
         ;; one candidate — this does not widen what gets curated or published
         ;; — but `kouhou.raw-archive` can now keep the source the briefing was
         ;; derived from. Before this it was parsed and dropped on the floor,
         ;; which left every published provenance URL dependent on the origin
         ;; server still serving that page later.
         (if item
           {:refused false :source-id (:source-id source) :item item
            :item-count (count items) :raw-feed xml}
           {:refused false :source-id (:source-id source) :item nil :item-count 0
            :raw-feed xml
            :fetch-error "feed parsed to zero items (unrecognized format or empty channel)"}))
       (catch #?(:clj Exception :cljs js/Error) e
         {:refused false :source-id (:source-id source) :item nil :item-count 0
          :fetch-error (#?(:clj .getMessage :cljs ex-message) e)})))))

#?(:clj
   (defn load-registry
     "Read the source registry JSON (`registry/sources.seed.json` — a vector of
     {\"source-id\" \"name\" \"host\" \"kind\" \"domain\" \"url\" \"read-only\"
     \"verified\" …} maps) via an injected `json-read` fn (e.g.
     `clojure.data.json/read-str`). File I/O only at this edge."
     [path json-read]
     (json-read (slurp (str path)) :key-fn keyword)))

(defn registry->host-set
  "The registry (as loaded by `load-registry`) -> the host whitelist set
  `kouhou.governor/check` / `kouhou.ingest/registered-source?` expect via
  context :registry — the REAL runtime whitelist, replacing reliance on the
  stale `kouhou.governor/default-registry` R0 fixture hosts."
  [registry]
  (set (map :host registry)))

(defn verified-sources
  "The subset of `registry` whose \"verified\" field is true — only these are
  eligible for a live fetch (mirrors kawaraban's `(filter :verified …)` in
  run_live_ingest.clj)."
  [registry]
  (filterv :verified registry))
