(ns kouhou.store-contract-test
  "MemStore ≡ DatomicStore contract — the same briefing record + ledger facts
  appear regardless of backend (in-process EAVT today; kotoba-server pod
  tomorrow). kouhou's analog of tashikame/tsumugu/sng store_contract_test."
  (:require [clojure.test :refer [deftest is testing]]
            [kouhou.store :as store]))

(deftest mem-and-datomic-agree-on-commit-and-ledger
  (testing "the same briefing + ledger fact land on both backends"
    (let [mem (store/seed-db)
          dat (store/datomic-store)]
      (doseq [s [mem dat]]
        (store/commit-briefing! s "src1" {:source-id "src1" :title "x" :summary "y"})
        (store/append-ledger! s {:t :committed :source "src1" :disposition :commit}))
      (is (= "x" (get (store/briefing mem "src1") :title)))
      (is (= "x" (get (store/briefing dat "src1") :title)))
      (is (= 1 (count (store/ledger mem))))
      (is (= 1 (count (store/ledger dat))))
      (is (= :committed (:t (last (store/ledger mem)))))
      (is (= :committed (:t (last (store/ledger dat))))))))

(deftest ledger-is-append-only
  (testing "ledger facts accumulate by seq, never overwritten"
    (let [s (store/seed-db)]
      (store/append-ledger! s {:t :committed :source "a"})
      (store/append-ledger! s {:t :hold      :source "b"})
      (is (= 2 (count (store/ledger s))))
      (is (= [:committed :hold] (map :t (store/ledger s)))))))
