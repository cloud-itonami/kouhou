(ns kouhou.live-fetch-test
  "kouhou — tests for the live HTTP fetch + RSS 2.0 / RSS 1.0 (RDF) / Atom 1.0
  feed scaffold (live_fetch.cljc, mirroring kawaraban's ADR-2607110200
  live_fetch.cljc). No network I/O anywhere in this suite — `parse-feed`
  operates on inline fixture text, and `fetch-source!`'s gate-refusal path is
  exercised with the gate OFF (the real default; this suite never sets
  KOUHOU_ALLOW_LIVE_INGEST)."
  (:require [clojure.test :refer [deftest is testing]]
            [kouhou.governor :as governor]
            [kouhou.ingest :as ingest]
            [kouhou.live-fetch :as live-fetch]))

;; ── fixtures ─────────────────────────────────────────────────────────────

(def rss-fixture
  ;; shape of most of the world-scope registry: US whitehouse.gov, DE
  ;; bundesregierung.de, EU presscorner, UN press.un.org — plain RSS 2.0.
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Example Government Wire</title>
    <item>
      <title>Cabinet approves new disaster-readiness budget</title>
      <link>https://example.gov/news/disaster-budget</link>
      <description><![CDATA[The Cabinet approved a new disaster-readiness budget today &amp; officials outlined next steps.]]></description>
      <pubDate>Wed, 09 Jul 2026 08:00:00 GMT</pubDate>
    </item>
    <item>
      <title>Trade talks resume between &quot;major&quot; partners</title>
      <link>https://example.gov/news/trade-talks</link>
      <description>Trade talks resumed between major partners this week, officials said.</description>
      <pubDate>Wed, 09 Jul 2026 06:30:00 GMT</pubDate>
    </item>
  </channel>
</rss>")

(def rdf-fixture
  ;; shape of 政府広報オンライン (gov-online.go.jp): RSS 1.0/RDF, dc:date instead
  ;; of pubDate, same flat <item> shape as RSS 2.0.
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns=\"http://purl.org/rss/1.0/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">
 <channel rdf:about=\"https://example.go.jp/rdf\">
  <title>政府広報オンライン（例）</title>
 </channel>
 <item rdf:about=\"https://example.go.jp/rdf/item1\">
  <title>令和8年度 衛生対策基本方針を公表</title>
  <link>https://example.go.jp/rdf/item1</link>
  <description>政府は令和8年度の衛生対策基本方針を公表した。</description>
  <dc:date>2026-07-10T02:41:00Z</dc:date>
 </item>
</rdf:RDF>")

(def atom-fixture
  ;; shape of GOV.UK (gov.uk news-and-communications.atom).
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<feed xmlns=\"http://www.w3.org/2005/Atom\">
  <title>News and communications</title>
  <entry>
    <title>New funding announced for regional infrastructure</title>
    <link rel=\"alternate\" href=\"https://example.gov.uk/news/infra-funding\"/>
    <summary>New funding was announced for regional infrastructure projects.</summary>
    <published>2026-07-09T05:00:00Z</published>
  </entry>
</feed>")

;; ── XML entity / CDATA cleanup ──────────────────────────────────────────

(deftest test-unescape-xml
  (is (= "Tom & \"Jerry\" <ok> it's" (live-fetch/unescape-xml "Tom &amp; &quot;Jerry&quot; &lt;ok&gt; it&apos;s"))))

(deftest test-strip-cdata
  (is (= "hello & world" (live-fetch/strip-cdata "<![CDATA[hello & world]]>")))
  (is (= "plain" (live-fetch/strip-cdata "plain"))))

;; ── feed parsing (RSS 2.0 / RDF / Atom) ──────────────────────────────────

(deftest test-parse-feed-detects-rss
  (let [items (live-fetch/parse-feed rss-fixture)]
    (is (= 2 (count items)))
    (is (= "Cabinet approves new disaster-readiness budget" (:title (first items))))
    (is (= "https://example.gov/news/disaster-budget" (:url (first items))))
    (is (= "The Cabinet approved a new disaster-readiness budget today & officials outlined next steps."
           (:raw (first items))))
    (is (pos? (:as-of (first items))))))

(deftest test-parse-feed-detects-rdf
  (testing "RSS 1.0/RDF (gov-online.go.jp shape): dc:date fallback"
    (let [items (live-fetch/parse-feed rdf-fixture)]
      (is (= 1 (count items)))
      (is (= "令和8年度 衛生対策基本方針を公表" (:title (first items))))
      (is (= "https://example.go.jp/rdf/item1" (:url (first items))))
      (is (pos? (:as-of (first items))) "falls back to dc:date when pubDate is absent"))))

(deftest test-parse-feed-detects-atom
  (testing "Atom 1.0 (gov.uk shape)"
    (let [items (live-fetch/parse-feed atom-fixture)]
      (is (= 1 (count items)))
      (is (= "New funding announced for regional infrastructure" (:title (first items))))
      (is (= "https://example.gov.uk/news/infra-funding" (:url (first items))))
      (is (= "New funding was announced for regional infrastructure projects." (:raw (first items)))))))

(deftest test-parse-feed-unrecognized-returns-empty
  (is (= [] (live-fetch/parse-feed "<not-a-feed/>"))))

(deftest test-parse-feed-sorts-newest-first
  (let [items (live-fetch/parse-feed rss-fixture)]
    (is (>= (:as-of (first items)) (:as-of (second items))))))

(deftest test-latest-item
  (is (= "Cabinet approves new disaster-readiness budget"
         (:title (live-fetch/latest-item (live-fetch/parse-feed rss-fixture)))))
  (is (nil? (live-fetch/latest-item []))))

;; ── date parsing ──────────────────────────────────────────────────────────

(deftest test-parse-date-rfc822
  (is (pos? (live-fetch/parse-date->epoch "Wed, 09 Jul 2026 08:00:00 GMT"))))

(deftest test-parse-date-iso8601
  (is (pos? (live-fetch/parse-date->epoch "2026-07-09T05:00:00Z"))))

(deftest test-parse-date-unrecognized-is-zero
  (is (= 0 (live-fetch/parse-date->epoch "not a date"))))

;; ── live-ingest gate (KOUHOU_ALLOW_LIVE_INGEST) ──────────────────────────

(deftest test-live-allowed-defaults-to-false
  (testing "the gate is OFF unless KOUHOU_ALLOW_LIVE_INGEST=1 (no real env mutation)"
    (is (false? (live-fetch/live-allowed? (fn [_] nil))))
    (is (false? (live-fetch/live-allowed? (fn [_] "0"))))
    (is (false? (live-fetch/live-allowed? (fn [_] "true"))))
    (is (true? (live-fetch/live-allowed? (fn [_] "1"))))))

(deftest test-fetch-source-refused-when-gate-closed
  ;; the real default: KOUHOU_ALLOW_LIVE_INGEST is unset in this test environment,
  ;; and this test also never touches real process env — allowed? is passed explicitly.
  (let [source {:source-id "gov-jp-press" :url "https://www.gov-online.go.jp/rss/index.rdf"}
        result (live-fetch/fetch-source! source #{"www.gov-online.go.jp"}
                                          (fn [_url] (throw (ex-info "must never be called" {})))
                                          false)]
    (is (true? (:refused result)))
    (is (re-find #"KOUHOU_ALLOW_LIVE_INGEST" (:reason result)))))

(deftest test-fetch-source-refused-when-host-not-registered
  (testing "registered-source? (kouhou.ingest, unchanged) still gates eligibility"
    (let [source {:source-id "rogue" :url "https://blog.example.net/feed.xml"}
          result (live-fetch/fetch-source! source #{"www.gov-online.go.jp"}
                                            (fn [_url] rss-fixture)
                                            true)]
      (is (true? (:refused result)))
      (is (re-find #"not in the public-sector" (:reason result))))))

(deftest test-fetch-source-with-gate-open-uses-injected-fetch-fn
  (testing "gate open + registered host -> parses via the injected fetch-fn, zero network I/O"
    (let [source {:source-id "gov-us-whitehouse" :url "https://www.whitehouse.gov/news/feed/"}
          result (live-fetch/fetch-source! source #{"www.whitehouse.gov"}
                                            (fn [url] (is (= "https://www.whitehouse.gov/news/feed/" url)) rss-fixture)
                                            true)]
      (is (false? (:refused result)))
      (is (= "gov-us-whitehouse" (:source-id result)))
      (is (= 2 (:item-count result)))
      (is (= "Cabinet approves new disaster-readiness budget" (:title (:item result)))))))

;; ── per-source error isolation ────────────────────────────────────────────

(deftest test-fetch-source-network-failure-is-isolated-not-thrown
  (testing "a fetch-fn that throws (timeout / DNS / etc.) -> a :fetch-error map, not an exception"
    (let [source {:source-id "gov-de-bundesregierung" :url "https://www.bundesregierung.de/service/rss/breg-de/1151244/feed.xml"}
          result (live-fetch/fetch-source! source #{"www.bundesregierung.de"}
                                            (fn [_url] (throw (ex-info "connection reset" {})))
                                            true)]
      (is (false? (:refused result)))
      (is (nil? (:item result)))
      (is (= "connection reset" (:fetch-error result))))))

(deftest test-fetch-source-empty-feed-is-a-fetch-error-not-a-throw
  (let [source {:source-id "kanpou" :url "https://www.kanpo.go.jp/"}
        result (live-fetch/fetch-source! source #{"www.kanpo.go.jp"}
                                          (fn [_url] "<html><body>not a feed</body></html>")
                                          true)]
    (is (false? (:refused result)))
    (is (nil? (:item result)))
    (is (some? (:fetch-error result)))))

;; ── registry helpers ──────────────────────────────────────────────────────

(deftest test-registry->host-set
  (is (= #{"a.gov" "b.gov"}
         (live-fetch/registry->host-set [{:host "a.gov"} {:host "b.gov"}]))))

(deftest test-verified-sources
  (is (= [{:source-id "a" :verified true}]
         (live-fetch/verified-sources [{:source-id "a" :verified true}
                                        {:source-id "b" :verified false}]))))

;; ── the whole point: live-fetched items flow through the SAME registered-source?
;; gate as the offline path — no new gate is invented here ────────────────────

(deftest test-live-fetched-item-flows-through-existing-registered-source-gate
  (let [items (live-fetch/parse-feed rss-fixture)
        hosts (live-fetch/registry->host-set [{:host "example.gov"}])]
    (is (every? #(ingest/registered-source? hosts (:url %)) items))
    (is (not (ingest/registered-source? governor/default-registry "https://www.whitehouse.gov/news/feed/"))
        "a real world-scope registry host is intentionally NOT in the R0 default-registry fixture")))

;; ─────────────────────────────────────────────────────────── transport
;;
;; These two guard the difference between "this source has no news today" and
;; "we could not read what it sent". Both failures arrived at the parser as
;; `feed parsed to zero items`, i.e. a FORMAT verdict on a TRANSPORT problem,
;; which is why they survived in the daily run for a week without anyone
;; being able to see them (measured 2026-08-18: 11 of 53 sources failing,
;; 5 of them for these two reasons).

(deftest the-user-agent-does-not-say-bot
  (testing "measured 2026-08-18 against alemarahenglish.af: every UA
            containing the substring `bot` returns 403 and every one without
            it returns 200 with the feed. The previous value ended in
            `... curator bot, ...` and that source failed every single day."
    (is (not (clojure.string/includes? (clojure.string/lower-case live-fetch/user-agent) "bot"))))

  (testing "and it still identifies this client honestly — the fix is to stop
            tripping a substring filter, NOT to impersonate a browser"
    (is (clojure.string/starts-with? live-fetch/user-agent "kouhou/"))
    (is (clojure.string/includes? live-fetch/user-agent "http"))
    (is (not (clojure.string/includes? live-fetch/user-agent "Mozilla")))))

(deftest gzip-bodies-are-decoded
  (let [gzip-bytes (fn [^String s]
                     (let [bos (java.io.ByteArrayOutputStream.)]
                       (with-open [gz (java.util.zip.GZIPOutputStream. bos)]
                         (.write gz (.getBytes s "UTF-8")))
                       (.toByteArray bos)))
        decode #'live-fetch/decode-body
        feed "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><item><title>x</title></item></channel></rss>"]

    (testing "declared by the header"
      (is (= feed (decode (gzip-bytes feed) "gzip"))))

    (testing "NOT declared — at least one source (UN press) gzips
              unconditionally and sends no content-encoding, so the magic
              bytes have to be enough"
      (is (= feed (decode (gzip-bytes feed) nil))))

    (testing "a plain body is passed through unchanged"
      (is (= feed (decode (.getBytes feed "UTF-8") nil))))

    (testing "and the result is what the parser can actually read — the bug
              was that it received compressed bytes and called them a format
              it did not recognise"
      (is (= 1 (count (live-fetch/parse-feed (decode (gzip-bytes feed) "gzip"))))))))
