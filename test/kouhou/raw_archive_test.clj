(ns kouhou.raw-archive-test
  "The corpus receipt: does the digest recorded in the EDN plane actually
  identify the bytes in the annex plane, and does `verify` say so when it
  does not? A receipt that only ever reports :ok is not evidence of custody,
  so the mismatch case is asserted by actually corrupting a byte."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kouhou.raw-archive :as sut])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "kouhou-raw" (into-array FileAttribute []))))

(defn- rm-rf [dir]
  (doseq [f (reverse (file-seq (io/file dir)))] (.delete ^java.io.File f)))

(def ^:private feed
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><item><title>t</title></item></channel></rss>")

(deftest receipt-identifies-the-bytes
  (let [dir (tmp-dir)]
    (try
      (let [r (sut/save! dir "2026-08-11" "eu-ec-presscorner" feed)]
        (testing "the file is where the receipt says"
          (is (.isFile (io/file (:path r))))
          (is (= (count (.getBytes feed "UTF-8")) (:bytes r))))
        (testing "verify confirms it"
          (is (= :ok (:status (sut/verify r)))))
        (testing "verify catches a changed byte"
          (spit (:path r) (str feed "<!-- tampered -->"))
          (let [v (sut/verify r)]
            (is (= :mismatch (:status v)))
            (is (not= (:expected v) (:actual v)))))
        (testing "a dropped (annexed-but-absent) file reports :absent, not :ok"
          (.delete (io/file (:path r)))
          (is (= :absent (:status (sut/verify r))))))
      (finally (rm-rf dir)))))

(deftest non-utf8-declaration-is-normalized
  (testing "an archived Shift_JIS feed does not keep a prolog that lies about it"
    (let [body "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?><rss><channel/></rss>"
          out  (sut/normalize-encoding-decl body)]
      (is (re-find #"encoding=\"UTF-8\"" out))
      (is (not (re-find #"Shift_JIS" out)))))
  (testing "an encoding= that is not in the prolog is left alone"
    (let [body "<rss><item><description>encoding=\"Shift_JIS\"</description></item></rss>"]
      (is (= body (sut/normalize-encoding-decl body)))))
  (testing "UTF-8 declarations are untouched"
    (is (= feed (sut/normalize-encoding-decl feed)))))

(deftest source-id-cannot-escape-the-corpus-directory
  (let [dir (tmp-dir)]
    (try
      (let [r (sut/save! dir "2026-08-11" "../../etc/passwd" feed)]
        (is (= ".._.._etc_passwd.xml" (.getName (io/file (:path r))))
            "separators are flattened, so the traversal becomes an ordinary name")
        (is (.startsWith (.getCanonicalPath (io/file (:path r)))
                         (.getCanonicalPath (io/file dir)))))
      (testing "an id that is nothing but dots cannot name the directory itself"
        (is (= "_unnamed" (sut/safe-name ".."))))
      (finally (rm-rf dir)))))
