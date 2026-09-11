(ns kouhou.operation-test
  "End-to-end operation behavior — the autonomous-publication doctrine as tests:
  low-confidence still publishes (with a transparency tag), not blocked; a
  request with no source-url no-ops and is held."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [kouhou.store :as store]
            [kouhou.advisor :as advisor]
            [kouhou.publisher :as publisher]
            [kouhou.operation :as op]))

(def ^:private reg-url "https://press.example.go.jp/p")

(defn- low-advisor []
  (reify advisor/Advisor
    (-organize [_ _ _] {:effect :assessment :confidence 0.1
                        :briefings [{:title "x" :summary "x" :source-url reg-url
                                     :domain :general :tags [] :confidence 0.1}]
                        :summary "" :rationale ""})))

(deftest low-confidence-still-publishes-with-tag
  (testing "low confidence does NOT block publication (autonomous); it is tagged"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:advisor (low-advisor) :publisher pub})
          r (g/run* a {:request {:op :source/digest :source-id "l1"
                                 :url reg-url :title "x" :raw "x"}
                       :context {:actor-id "kouhou" :phase 1}} {:thread-id "l1"})]
      (is (= :commit (get-in r [:state :disposition])) "low confidence → still publishes")
      (is (= 1 (count @(:a pub))))
      (is (some #(= :low-confidence (:rule %))
                (-> (store/ledger s) last :warnings))))))

(deftest no-url-noops-and-is-held
  (testing "a request with no source-url → mock advisor :noop → governor :no-actuation → hold"
    (let [s (store/seed-db) pub (publisher/mock-publisher (atom []))
          a (op/build s {:publisher pub})
          r (g/run* a {:request {:op :source/digest :source-id "e1"
                                 :url "" :title "x" :raw "x"}
                       :context {:actor-id "kouhou" :phase 1}} {:thread-id "e1"})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (zero? (count @(:a pub)))))))
