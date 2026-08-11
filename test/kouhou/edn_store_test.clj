(ns kouhou.edn-store-test
  "EdnStore ≡ MemStore on the Store contract, PLUS the property MemStore does
  not have: the records are still there after the store object is gone.

  The contract test alone would pass on a store that wrote nothing — every
  assertion in it is satisfied by the in-memory view. So the test that matters
  here builds a SECOND store over the same directory and asserts against that
  one; only bytes on disk can carry a value across that boundary."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kouhou.canonical :as canon]
            [kouhou.edn-store :as sut]
            [kouhou.store :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "kouhou-edn-store" (into-array FileAttribute []))))

(defn- rm-rf [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(defn- fixed-clock
  "A clock that hands out successive instants, so shard bytes are
  deterministic and two facts written in the same test never share a
  timestamp."
  [instants]
  (let [remaining (atom instants)]
    (fn [] (let [[x & more] @remaining]
             (reset! remaining (or more [x]))
             x))))

(deftest survives-the-process
  (testing "a second store over the same directory sees the first one's writes"
    (let [dir (tmp-dir)]
      (try
        (let [s (sut/edn-store dir {:clock (fixed-clock ["2026-08-11T00:00:00Z"
                                                         "2026-08-11T00:00:01Z"
                                                         "2026-08-11T00:00:02Z"])})]
          (store/commit-briefing! s "src1" {:source-id "src1" :title "x" :summary "y"})
          (store/append-ledger! s {:t :committed :source "src1" :disposition :commit}))
        ;; the store object above is now unreferenced — everything below can
        ;; only come from the files
        (let [s2 (sut/edn-store dir)]
          (is (= "x" (:title (store/briefing s2 "src1"))))
          (is (= 1 (count (store/ledger s2))))
          (is (= :committed (:t (first (store/ledger s2))))))
        (finally (rm-rf dir))))))

(deftest agrees-with-memstore
  (testing "same protocol behavior as the in-memory backend"
    (let [dir (tmp-dir)]
      (try
        (let [mem (store/seed-db)
              edn (sut/edn-store dir)]
          (doseq [s [mem edn]]
            (store/commit-briefing! s "b" {:source-id "b" :title "B"})
            (store/commit-briefing! s "a" {:source-id "a" :title "A"})
            (store/append-ledger! s {:t :committed :source "b"})
            (store/append-ledger! s {:t :hold :source "a"}))
          (is (= (store/briefing mem "a") (store/briefing edn "a")))
          (is (= (mapv :source-id (store/all-briefings mem))
                 (mapv :source-id (store/all-briefings edn))))
          (is (= (mapv :t (store/ledger mem)) (mapv :t (store/ledger edn)))))
        (finally (rm-rf dir))))))

(deftest briefings-are-appended-not-overwritten
  (testing "re-committing a source keeps the earlier briefing in the shard"
    (let [dir (tmp-dir)]
      (try
        (let [s (sut/edn-store dir {:clock (fixed-clock ["2026-08-11T00:00:00Z"
                                                         "2026-08-11T06:00:00Z"])})]
          (store/commit-briefing! s "src1" {:source-id "src1" :title "first"})
          (store/commit-briefing! s "src1" {:source-id "src1" :title "second"})
          ;; the store's read view is the latest…
          (is (= "second" (:title (store/briefing s "src1"))))
          ;; …and the history is still on disk
          (let [rows (sut/read-shards (:briefings-dir s))]
            (is (= 2 (count rows)))
            (is (= ["first" "second"] (mapv #(get-in % [:payload :title]) rows)))))
        (finally (rm-rf dir))))))

(deftest shards-are-split-by-utc-day
  (testing "an instant with an offset files under its UTC day, not its local one"
    (let [dir (tmp-dir)]
      (try
        ;; 2026-08-11T08:30+09:00 is 2026-08-10T23:30Z — the previous UTC day.
        (let [s (sut/edn-store dir {:clock (fixed-clock ["2026-08-11T08:30:00+09:00"])})]
          (store/append-ledger! s {:t :committed :source "s"})
          (is (= ["2026-08-10.edn"]
                 (mapv #(.getName (io/file %)) (:ledger (sut/shard-paths s))))))
        (finally (rm-rf dir))))))

(deftest bytes-are-canonical
  (testing "map key order does not depend on how the map was built"
    ;; 9 keys: past the point where Clojure switches from array-map to
    ;; hash-map, which is where naive pr-str stops being stable.
    (let [ks (mapv #(keyword (str "k" %)) (range 9))
          a  (reduce (fn [m k] (assoc m k 1)) {} ks)
          b  (reduce (fn [m k] (assoc m k 1)) {} (reverse ks))]
      (is (= (canon/canonical-str a) (canon/canonical-str b))
          "two equal maps must serialize to the same bytes")
      (is (str/starts-with? (canon/canonical-str a) "{:k0 1")
          "keys sort by printed form"))
    (testing "mixed key types do not throw the way compare would"
      (is (string? (canon/canonical-str {:a 1 "b" 2 3 4}))))
    (testing "a record round-trips through the line format"
      (let [v {:kouhou/kind :ledger :source "s" :nested {:z 1 :a [1 2 {:q "r"}]}}]
        (is (= [v] (canon/read-lines read-string (canon/canonical-str v))))))))

(deftest a-corrupt-line-is-loud
  (testing "a malformed shard line throws instead of being silently dropped"
    (is (thrown? Exception (canon/read-lines read-string "{:a 1}\n{:b \n")))))
