(ns kouhou.verify-corpus-test
  "A verifier that only ever reports OK is indistinguishable from one that
  reports nothing. So the case asserted here is the FAILING one: a corpus file
  whose bytes no longer match its receipt has to come back `:mismatch`. The
  passing case is asserted alongside it in the same test, because a detector
  that fails everything is equally useless."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kouhou.canonical :as canon]
            [kouhou.raw-archive :as raw]
            [kouhou.verify-corpus :as sut])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "kouhou-verify" (into-array FileAttribute []))))

(defn- rm-rf [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(defn- corpus! [data-dir receipts]
  (let [f (io/file data-dir "corpus" "2026-08-11.edn")]
    (io/make-parents f)
    (spit f (apply str (map #(canon/canonical-str
                              {:kouhou/kind :corpus
                               :kouhou/as-of "2026-08-11T00:00:00Z"
                               :source-id "s" :raw %})
                            receipts)))))

(deftest detects-a-changed-byte
  (let [root (tmp-dir)
        data (str (io/file root "data"))
        raw* (str (io/file root "raw"))]
    (try
      (let [good (raw/save! raw* "2026-08-11" "good" "<rss><channel/></rss>")
            bad  (raw/save! raw* "2026-08-11" "bad"  "<rss><channel/></rss>")]
        (corpus! data [good bad])
        (testing "an untouched corpus passes"
          (let [r (sut/report (mapv sut/check (sut/receipts data)))]
            (is (= 2 (:receipts r)))
            (is (= 2 (get-in r [:content :ok])))
            (is (empty? (:failures r)))))
        (testing "changing one byte is caught, and only that one"
          (spit (:path bad) "<rss><channel><item/></channel></rss>")
          (let [r (sut/report (mapv sut/check (sut/receipts data)))]
            (is (= 1 (count (:failures r))))
            (is (= (:path bad) (:path (first (:failures r)))))
            (is (= :mismatch (:content (first (:failures r)))))))
        (testing "dropped bytes are absent, not failed"
          (spit (:path bad) "<rss><channel/></rss>")  ; restore
          (.delete (io/file (:path good)))
          (let [r (sut/report (mapv sut/check (sut/receipts data)))]
            (is (= 1 (get-in r [:content :absent])))
            (is (empty? (:failures r))
                "an annexed file whose content was dropped must not fail the check"))))
      (finally (rm-rf root)))))

(deftest plain-files-are-reported-as-unchecked-not-as-agreement
  (testing "outside an annex there is no key digest to compare, and the
            verifier says so rather than counting it as ok"
    (let [root (tmp-dir)
          raw* (str (io/file root "raw"))]
      (try
        (let [r (sut/check (raw/save! raw* "2026-08-11" "s" "<rss/>"))]
          (is (= :not-annexed (:key-digest r)))
          (is (= :ok (:content r))))
        (finally (rm-rf root))))))
