(ns kouhou.verify-corpus
  "Check the corpus instead of asserting it.

  Every corpus receipt in `data/corpus/*.edn` names an archived feed by path,
  size and sha256. This walks them and reports, per receipt, whether that file
  is still the thing the receipt describes. Three independent checks, because
  each can pass while another fails:

  1. **annex key** — `raw/<day>/<id>.xml` is a symlink into
     `.git/annex/objects/…/SHA256E-s<bytes>--<digest>.xml`. The digest in that
     key must equal the digest in the receipt. This holds even when the bytes
     have been dropped locally, so it is the one check that still works on a
     thin clone.
  2. **content** — when the bytes ARE present, hash them. An annex key is a
     claim about content; only reading it tests the claim.
  3. **presence** — how many receipts have bytes here at all.

  Absent bytes are NOT a failure. Dropping them is the point of the annex —
  `datalad drop raw/` frees the disk and `datalad get` brings them back from
  s3.kotobase.net. Reporting absence as failure would train the operator to
  ignore this command. A digest MISMATCH is a failure: it means a file was
  replaced without its receipt being updated, which is the one thing the
  digest exists to catch.

  This does not verify that the object plane still HOLDS the bytes — that is
  `git annex fsck --from kotobase`, a different question (custody, not
  identity), and the superproject keeps them separate for the same reason
  (`scripts/annex-custody-verify.cljs`).

      clojure -M:verify-corpus"
  (:require [clojure.java.io :as io]
            [kouhou.edn-store :as edn-store]
            [kouhou.raw-archive :as raw])
  (:gen-class))

(defn- annex-key-digest
  "The sha256 out of a SHA256E annex key, when `path` is an annex symlink.
  nil for a plain file (or a link that is not an annex object) — the caller
  reports that as unchecked rather than as agreement."
  [path]
  (let [f (io/file path)]
    (when (java.nio.file.Files/isSymbolicLink (.toPath f))
      (let [target (str (java.nio.file.Files/readSymbolicLink (.toPath f)))]
        (second (re-find #"SHA256E-s\d+--([0-9a-f]{64})" target))))))

(defn check
  "One receipt → a result map with :key-digest and :content statuses."
  [{:keys [path sha256] :as receipt}]
  (let [kd (annex-key-digest path)]
    {:path       path
     :key-digest (cond (nil? kd)       :not-annexed
                       (= kd sha256)   :ok
                       :else           :mismatch)
     :content    (:status (raw/verify receipt))}))

(defn receipts
  "Every `:raw` receipt across the corpus shards under `<data-dir>/corpus`."
  [data-dir]
  (->> (edn-store/read-shards (str (io/file data-dir "corpus")))
       (keep :raw)))

(defn report [results]
  (let [by  (fn [k v] (count (filter #(= v (k %)) results)))
        bad (filter #(or (= :mismatch (:key-digest %))
                         (= :mismatch (:content %))) results)]
    {:receipts     (count results)
     :key-digest   {:ok (by :key-digest :ok)
                    :mismatch (by :key-digest :mismatch)
                    :not-annexed (by :key-digest :not-annexed)}
     :content      {:ok (by :content :ok)
                    :mismatch (by :content :mismatch)
                    :absent (by :content :absent)}
     :failures     (vec bad)}))

(defn -main [& args]
  (let [data-dir (or (first args) (System/getenv "KOUHOU_DATA_DIR") "data")
        rs       (receipts data-dir)
        r        (report (mapv check rs))]
    (println "=== kouhou verify-corpus ===")
    (println (str (:receipts r) " receipts in " data-dir "/corpus"))
    (println (str "  annex key digest: " (get-in r [:key-digest :ok]) " ok, "
                  (get-in r [:key-digest :mismatch]) " mismatch, "
                  (get-in r [:key-digest :not-annexed]) " not annexed"))
    (println (str "  content:          " (get-in r [:content :ok]) " ok, "
                  (get-in r [:content :mismatch]) " mismatch, "
                  (get-in r [:content :absent]) " absent (dropped — not a failure)"))
    (doseq [f (:failures r)]
      (println (str "  FAIL " (:path f) " key-digest=" (:key-digest f)
                    " content=" (:content f))))
    (when (seq (:failures r))
      (System/exit 1))
    (when (zero? (:receipts r))
      ;; An empty corpus passing every check reads exactly like a healthy one.
      (println "no receipts found — nothing was checked")
      (System/exit 1))))
