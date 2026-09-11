(ns kouhou.run-live-ingest-test
  "Pure-fn tests for kouhou.run-live-ingest — the fetch->actor-graph glue and
  its safety engineering (KOUHOU_ALLOW_LIVE_INGEST gate, per-source error
  isolation). No network I/O anywhere in this suite: `run-source!`'s 5-arg
  form takes an injected fetch-fn + explicit `allowed?`, the same seam
  `kouhou.live-fetch/fetch-source!` itself exposes, mirroring the discipline
  of kawaraban's own `run_live_ingest_test.clj` (gates and pure transforms
  are unit-tested; real I/O edges are not mocked into a false sense of
  coverage)."
  (:require [clojure.test :refer [deftest is testing]]
            [kouhou.advisor :as advisor]
            [kouhou.operation :as op]
            [kouhou.publisher :as publisher]
            [kouhou.store :as store]
            [kouhou.run-live-ingest :as sut]))

(def rss-fixture
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<rss version=\"2.0\">
  <channel>
    <title>Example Government Wire</title>
    <item>
      <title>Cabinet approves new disaster-readiness budget</title>
      <link>https://example.gov/news/disaster-budget</link>
      <description>The Cabinet approved a new disaster-readiness budget today.</description>
      <pubDate>Wed, 09 Jul 2026 08:00:00 GMT</pubDate>
    </item>
  </channel>
</rss>")

(defn- fresh-actor [publisher]
  (op/build (store/seed-db) {:publisher publisher}))

(deftest test-run-source-refused-when-gate-closed
  (testing "gate closed (allowed? false) -> refused, never touches the graph or the fetch-fn"
    (let [source {:source-id "gov-jp-press" :url "https://www.gov-online.go.jp/rss/index.rdf"}
          pub    (publisher/mock-publisher (atom []))
          actor  (fresh-actor pub)
          result (sut/run-source! source #{"www.gov-online.go.jp"} actor
                                   (fn [_] (throw (ex-info "must never be called" {})))
                                   false)]
      (is (true? (:refused result)))
      (is (re-find #"KOUHOU_ALLOW_LIVE_INGEST" (:reason result)))
      (is (zero? (count @(:a pub)))))))

(deftest test-run-source-gate-open-registered-clean-briefing-commits-and-publishes
  (testing "gate open + registered host + governor-clean -> commit + publish, via mock-advisor (the framework default)"
    (let [source {:source-id "gov-us-whitehouse" :url "https://www.whitehouse.gov/news/feed/"}
          hosts  #{"www.whitehouse.gov" "example.gov"}
          pub    (publisher/mock-publisher (atom []))
          actor  (fresh-actor pub)
          result (sut/run-source! source hosts actor (fn [_url] rss-fixture) true)]
      (is (= "gov-us-whitehouse" (:source-id result)))
      (is (= :commit (:disposition result)))
      (is (true? (:published? result)))
      (is (= "https://example.gov/news/disaster-budget" (:item-url result)))
      (is (= 1 (count @(:a pub)))))))

(deftest test-run-source-item-url-not-registered-is-held-not-worked-around
  (testing "the FEED's own host may be registered while an individual ITEM's link host is not
            (e.g. a redirect/shortlink) -- the PublicInfoGovernor's :source-not-in-registry
            gate on the item's own url still applies, and this is reported as a real HOLD,
            never bypassed."
    (let [source {:source-id "gov-us-whitehouse" :url "https://www.whitehouse.gov/news/feed/"}
          ;; only the FEED host is registered; the article link's host (example.gov) is not
          hosts  #{"www.whitehouse.gov"}
          pub    (publisher/mock-publisher (atom []))
          actor  (fresh-actor pub)
          result (sut/run-source! source hosts actor (fn [_url] rss-fixture) true)]
      (is (= :hold (:disposition result)))
      (is (false? (:published? result)))
      (is (zero? (count @(:a pub))) "governor hold -> never published"))))

(deftest test-run-source-per-source-network-failure-is-isolated
  (testing "a fetch-fn that throws -> a :fetch-error result map, never an uncaught exception"
    (let [source {:source-id "gov-de-bundesregierung" :url "https://www.bundesregierung.de/service/rss/breg-de/1151244/feed.xml"}
          pub    (publisher/mock-publisher (atom []))
          actor  (fresh-actor pub)
          result (sut/run-source! source #{"www.bundesregierung.de"} actor
                                   (fn [_url] (throw (ex-info "connection reset" {})))
                                   true)]
      (is (= "gov-de-bundesregierung" (:source-id result)))
      (is (= "connection reset" (:fetch-error result)))
      (is (nil? (:disposition result))))))

(deftest test-run-source-graph-exception-is-caught-and-reported
  (testing "even a graph-node-level exception (e.g. a broken advisor) is caught -> :error, not thrown"
    (let [source {:source-id "boom" :url "https://www.whitehouse.gov/news/feed/"}
          hosts  #{"www.whitehouse.gov" "example.gov"}
          broken-advisor (reify advisor/Advisor
                            (-organize [_ _ _] (throw (ex-info "advisor exploded" {}))))
          actor  (op/build (store/seed-db) {:advisor broken-advisor})
          result (sut/run-source! source hosts actor (fn [_url] rss-fixture) true)]
      (is (= "boom" (:source-id result)))
      (is (some? (:error result))))))

(deftest test-run-source-empty-feed-is-a-fetch-error
  (let [source {:source-id "kanpou" :url "https://www.kanpo.go.jp/"}
        pub    (publisher/mock-publisher (atom []))
        actor  (fresh-actor pub)
        result (sut/run-source! source #{"www.kanpo.go.jp"} actor
                                 (fn [_url] "<html><body>not a feed</body></html>")
                                 true)]
    (is (= "kanpou" (:source-id result)))
    (is (some? (:fetch-error result)))
    (is (zero? (count @(:a pub))))))

(deftest test-real-publisher-wires-collection-and-identity
  (testing "real-publisher builds a kouhou.aozora Publisher record (no network call made by
            merely constructing it -- publish! is what performs I/O, not this)"
    (let [pub (sut/real-publisher {:did "did:key:ztest" :private-key nil})]
      (is (satisfies? publisher/Publisher pub)))))
