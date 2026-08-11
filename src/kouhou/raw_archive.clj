(ns kouhou.raw-archive
  "The bytes plane: the fetched feed itself, kept out of git history.

  A briefing is a faithful summary WITH PROVENANCE, and a provenance URL is
  only as good as the page behind it. Government press pages move, feeds roll
  items off the end, and ministries reorganize their sites — so a briefing
  published in August whose source URL 404s in October can no longer be
  checked against what it summarized. Keeping the fetched feed answers that:
  the summary and the thing it was made from are both still here.

  ## Why annex and not git

  201 registered sources × one feed each is on the order of 10 MB per full
  pass. Committed to git that is permanent history in a repo whose code is
  ~200 KB; a year of daily passes would make cloning the ACTOR require
  downloading its CORPUS. So the superproject's large-binary rule applies:
  raw bytes live in git-annex (pointer in git, bytes in the S3-compatible
  object plane at s3.kotobase.net), while the small semantic EDN — the
  briefings and the ledger — stays an ordinary git blob where it can be
  diffed and reviewed.

  ## The receipt is what makes the two planes one thing

  `save!` returns `{:path :sha256 :bytes}` and the caller writes that INTO the
  briefing shard. That is the join: given a briefing you can name the exact
  feed it came from and verify the bytes, and given a corpus file you can find
  every briefing derived from it. Without the digest the pairing would be by
  filename, which proves nothing — an annexed file that was silently replaced
  has the same name.

  ## What is stored is the decoded feed, not the wire bytes

  `live-fetch/jvm-http-get` returns a String: the JDK has already decoded the
  response using the charset from `Content-Type` (or UTF-8). We re-encode that
  as UTF-8, so a Shift_JIS feed is archived as UTF-8 text and its byte-level
  identity with the origin server's response is NOT preserved. Say it plainly
  rather than let the digest imply more than it proves: the digest attests to
  what kouhou parsed, which is what the briefing was derived from, and that is
  the claim the receipt needs to support.

  A feed that declares a non-UTF-8 encoding in its XML prolog gets that
  declaration rewritten to UTF-8 on the way in. Leaving it would produce an
  archive file that lies about itself — bytes in one encoding, a prolog naming
  another — which every XML parser downstream would then decode wrongly."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn sha256-hex
  "Lowercase hex sha256 of `bytes`."
  [^bytes b]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") b)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(def ^:private xml-decl-encoding #"(?i)(<\?xml[^>]*?encoding=\")([^\"]+)(\")")

(defn normalize-encoding-decl
  "Rewrite a non-UTF-8 `encoding=` in the XML prolog to UTF-8. Only the prolog
  (the first declaration, and only if the document starts with one) — an
  `encoding=` appearing later is content, not a declaration about the file."
  [^String body]
  (if (str/starts-with? (str/triml body) "<?xml")
    (str/replace-first body xml-decl-encoding
                       (fn [[whole pre enc post]]
                         (if (= "utf-8" (str/lower-case enc))
                           whole
                           (str pre "UTF-8" post))))
    body))

(defn safe-name
  "A source-id reduced to a filename. Registry source-ids are already
  `[a-z0-9-]`, but a registry is data and data changes: an id containing `/`
  or `..` would otherwise write outside the corpus directory."
  [^String source-id]
  (let [s (str/replace (or source-id "") #"[^A-Za-z0-9._-]" "_")]
    (if (str/blank? (str/replace s "." "")) "_unnamed" s)))

(defn save!
  "Write one fetched feed to `<raw-dir>/<day>/<source-id>.xml` and return its
  receipt `{:path :sha256 :bytes}`. `:path` is the path as written, verbatim —
  nothing is rebased onto a root the caller did not give, so the receipt names
  the file the run actually produced."
  [raw-dir day source-id ^String body]
  (let [b    (.getBytes (normalize-encoding-decl body) "UTF-8")
        f    (io/file raw-dir day (str (safe-name source-id) ".xml"))]
    (io/make-parents f)
    (with-open [o (io/output-stream f)] (.write o b))
    {:path   (.getPath f)
     :sha256 (sha256-hex b)
     :bytes  (alength b)}))

(defn verify
  "Check one receipt against the file on disk.

  Returns `{:status :ok|:mismatch|:absent :path … }`. `:absent` is not a
  failure by itself — an annexed file whose bytes have been dropped locally is
  absent on purpose, and that is the whole point of the annex. It IS a failure
  if the corpus is supposed to be present, which is the caller's call to make,
  so this fn reports and does not decide."
  [{:keys [path sha256 bytes] :as receipt}]
  (let [f (io/file path)]
    (cond
      (not (.isFile f))
      {:status :absent :path path :receipt receipt}

      :else
      (let [b (with-open [in (io/input-stream f)] (.readAllBytes in))
            h (sha256-hex b)]
        (if (and (= h sha256) (= (alength b) bytes))
          {:status :ok :path path :sha256 h}
          {:status :mismatch :path path :expected sha256 :actual h
           :expected-bytes bytes :actual-bytes (alength b)})))))
