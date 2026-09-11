(ns kouhou.governor-contract-test
  "The public-info publication contract as executable tests. Invariant: kouhou
  NEVER publishes a briefing the PublicInfoGovernor rejects; every published
  briefing cites a REGISTERED public-sector/public-interest source (provenance);
  unregistered-source / no-provenance / commercial-content / catastrophe-veto /
  no-actuation proposals are held (recorded as a hold, never published).
  Publication is autonomous by default (ADR-2606281500)."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [kouhou.store :as store]
            [kouhou.advisor :as advisor]
            [kouhou.publisher :as publisher]
            [kouhou.operation :as op]))

(def ^:private reg-url "https://press.example.go.jp/2026/0702")

(defn- fresh []
  (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))]
    [s pub (op/build s {:publisher pub})]))

(defn- run [actor sid url title raw]
  (g/run* actor
          {:request {:op :source/digest :source-id sid :url url :title title :raw raw}
           :context {:actor-id "kouhou" :phase 1}}
          {:thread-id sid}))

(defn- basis [s] (-> (store/ledger s) last :basis))
(defn- published-count [pub] (count @(:a pub)))
(defn- with-advisor [s pub adv] (op/build s {:advisor adv :publisher pub}))
(defn- bad-advisor [briefings]
  (reify advisor/Advisor
    (-organize [_ _ _] {:effect :assessment :confidence 0.9
                        :briefings briefings :summary "" :rationale ""})))

(defn- one-briefing [src-url title]
  {:title title :summary "s" :source-url src-url :domain :general :tags [] :confidence 0.9})

(deftest registered-clean-briefing-publishes
  (testing "a registered-source, governor-clean briefing commits + publishes"
    (let [[s pub actor] (fresh)
          r (run actor "gov-jp-press" reg-url "衛生方針" "政府は…")]
      (is (= :commit (get-in r [:state :disposition])))
      (is (= "衛生方針" (get (store/briefing s "gov-jp-press") :title)))
      (is (= 1 (published-count pub))))))

(deftest unregistered-source-is-held
  (testing "a briefing whose host is NOT in the registry → HOLD (:source-not-in-registry)"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor [(one-briefing "https://blog.example.net/p" "x")]))
          r (g/run* a2 {:request {:op :source/digest :source-id "u1"
                                  :url "https://blog.example.net/p" :title "x" :raw "x"}
                        :context {:actor-id "kouhou" :phase 1}} {:thread-id "u1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:source-not-in-registry} (basis s)))
      (is (zero? (published-count pub)) "never published")
      (is (nil? (store/briefing s "u1")) "nothing recorded on hold"))))

(deftest no-provenance-is-held
  (testing "a briefing with a blank source-url → HOLD (:no-provenance)"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor [(one-briefing "" "x")]))
          r (g/run* a2 {:request {:op :source/digest :source-id "np1"
                                  :url "" :title "x" :raw "x"}
                        :context {:actor-id "kouhou" :phase 1}} {:thread-id "np1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:no-provenance} (basis s))))))

(deftest commercial-content-is-held
  (testing "a briefing with ad/sponsored markers → HOLD (:commercial-content)"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor [(one-briefing reg-url "<AD>特集")]))
          r (g/run* a2 {:request {:op :source/digest :source-id "ad1"
                                  :url reg-url :title "<AD>特集" :raw "x"}
                        :context {:actor-id "kouhou" :phase 1}} {:thread-id "ad1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:commercial-content} (basis s))))))

(deftest catastrophe-veto-is-held
  (testing "a briefing hitting the Rider §2 catastrophe-veto → HOLD"
    (let [[s pub _] (fresh)
          a2 (with-advisor s pub (bad-advisor [(one-briefing reg-url "<CAT>")]))
          r (g/run* a2 {:request {:op :source/digest :source-id "v1"
                                  :url reg-url :title "<CAT>" :raw "x"}
                        :context {:actor-id "kouhou" :phase 1}} {:thread-id "v1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:catastrophe-veto} (basis s))))))

(deftest no-actuation-is-held
  (testing "a proposal that tries to actuate (not curate) → HOLD (:no-actuation)"
    (let [[s pub _] (fresh)
          bad (reify advisor/Advisor
                (-organize [_ _ _] {:effect :grant-access :confidence 0.9
                                    :briefings [] :summary "" :rationale ""}))
          a2 (with-advisor s pub bad)
          r (g/run* a2 {:request {:op :source/digest :source-id "n1"
                                  :url reg-url :title "x" :raw "x"}
                        :context {:actor-id "kouhou" :phase 1}} {:thread-id "n1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (some #{:no-actuation} (basis s))))))

(deftest phase0-records-but-does-not-publish
  (testing "phase 0 (observe): a clean briefing is recorded but NOT published"
    (let [[s pub actor] (fresh)
          r (g/run* actor {:request {:op :source/digest :source-id "p0"
                                     :url reg-url :title "x" :raw "x"}
                           :context {:actor-id "kouhou" :phase 0}} {:thread-id "p0"})]
      (is (= :commit (get-in r [:state :disposition])) "briefing recorded")
      (is (= "x" (get (store/briefing s "p0") :title)))
      (is (zero? (published-count pub)) "phase 0 → shadow, no publish"))))
