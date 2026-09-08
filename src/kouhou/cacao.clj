(ns kouhou.cacao
  "Agent-side CACAO issuance (JVM). The kouhou actor mints its OWN
  server-verifiable CACAO to authenticate a publish to an aozora PDS
  (com.atproto.repo.createRecord) — no human-handed token. Ported from
  `tsumugu.cacao` / `tashikame.cacao` (keep in sync); the SIWE/wire builders
  below are a faithful copy of the proven byte-exact pure functions in
  `kotoba.cacao` (kotoba-auth / kotoba-wasm), and the crypto is JDK Ed25519 +
  a minimal CBOR encoder.

  Per-actor key model: kouhou generates + persists its OWN Ed25519 key
  (self-sovereign, no owner hand-off). A depth-1 self-minted CACAO (iss = the
  actor's own DID) is authorized by construction. Publication is the actor's
  own speech (ADR-2606281500, 種をまく); the revocable member CACAO leash
  carried in the publish call is the off-switch, not a per-post approval.

  The private key is persisted to `.<actor>/identity.edn` (gitignored) — NEVER
  commit a private key."
  (:require [clojure.edn :as edn]
            [kotoba.lang.text :as str])
  (:import [java.security KeyPairGenerator MessageDigest Signature KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec]
           [java.io ByteArrayOutputStream]
           [java.util Base64]))

(def ^:private cap->op {:cap/read "datom:read" :cap/transact "datom:transact" :cap/admin "tx:create"})

(defn grant->resources [{:keys [cap scope]}]
  [(str "kotoba://op/" (cap->op cap)) (str "kotoba://graph/" scope)])

(defn grant->payload [grant {:keys [iss aud nonce issued-at expiry domain version statement]
                             :or {domain "gftd.office" version "1"}}]
  {:iss iss :aud aud :issued-at issued-at :expiry expiry :nonce nonce
   :domain domain :statement statement :version version
   :resources (grant->resources grant)})

(defn- iss-address [iss] (last (str/split iss #":")))
(defn- iss-chain-id [iss]
  (if (str/starts-with? iss "did:key:") "1"
      (let [segs (str/split iss #":")] (if (>= (count segs) 2) (nth segs (- (count segs) 2)) "1"))))

(defn siwe-message [{:keys [iss aud issued-at expiry nonce domain statement version resources]}]
  (->> (concat
        [(str domain " wants you to sign in with your Ethereum account:") (iss-address iss) ""]
        (when statement [statement ""])
        [(str "URI: " aud) (str "Version: " version) (str "Chain ID: " (iss-chain-id iss))
         (str "Nonce: " nonce) (str "Issued At: " issued-at)]
        (when expiry [(str "Expiration Time: " expiry)])
        (when (seq resources) (cons "Resources:" (map #(str "- " %) resources))))
       (str/join "\n")))

(defn ->wire [payload sig-b64]
  {"h" {"t" "eip4361"}
   "p" (cond-> {"iss" (:iss payload) "aud" (:aud payload) "iat" (:issued-at payload)
                "nonce" (:nonce payload) "domain" (:domain payload)
                "version" (:version payload) "resources" (:resources payload)}
         (:expiry payload)    (assoc "exp" (:expiry payload))
         (:statement payload) (assoc "statement" (:statement payload)))
   "s" {"t" "EdDSA" "s" (or sig-b64 "")}})

(defn- cbor-head [^ByteArrayOutputStream o major n]
  (cond (< n 24)    (.write o (int (+ (bit-shift-left major 5) n)))
        (< n 256)   (do (.write o (int (+ (bit-shift-left major 5) 24))) (.write o (int n)))
        (< n 65536) (do (.write o (int (+ (bit-shift-left major 5) 25)))
                        (.write o (int (bit-and (unsigned-bit-shift-right n 8) 0xff)))
                        (.write o (int (bit-and n 0xff))))
        :else (throw (ex-info "cbor len too big" {:n n}))))

(defn- cbor-val [^ByteArrayOutputStream o v]
  (cond
    (string? v)     (let [b (.getBytes ^String v "UTF-8")] (cbor-head o 3 (alength b)) (.write o b 0 (alength b)))
    (map? v)        (do (cbor-head o 5 (count v)) (doseq [[k vv] v] (cbor-val o (name k)) (cbor-val o vv)))
    (sequential? v) (do (cbor-head o 4 (count v)) (doseq [x v] (cbor-val o x)))
    :else           (cbor-val o (str v))))

(defn- cbor-bytes ^bytes [v]
  (let [o (ByteArrayOutputStream.)] (cbor-val o v) (.toByteArray o)))

(def ^:private b58 "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- base58btc [^bytes data]
  (let [zeros (count (take-while zero? data))
        sb (StringBuilder.) fifty8 (java.math.BigInteger/valueOf 58)]
    (loop [n (java.math.BigInteger. 1 data)]
      (when (pos? (.signum n))
        (.append sb (.charAt b58 (.intValue (.mod n fifty8))))
        (recur (.divide n fifty8))))
    (dotimes [_ zeros] (.append sb \1))
    (.toString (.reverse sb))))

(defn- raw-pub
  "Raw 32-byte Ed25519 public key (last 32 bytes of the X.509 SPKI encoding)."
  ^bytes [pub]
  (let [enc (.getEncoded pub)] (java.util.Arrays/copyOfRange enc (- (alength enc) 32) (alength enc))))

(defn- did-key [pub]
  (let [raw (raw-pub pub)
        framed (byte-array (concat [(unchecked-byte 0xED) (unchecked-byte 0x01)] (seq raw)))]
    (str "did:key:z" (base58btc framed))))

(def ^:private b32 "abcdefghijklmnopqrstuvwxyz234567")

(defn- sha256 ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn- base32-lower-no-pad
  "CIDv1 base32-lower, no padding (multibase 'b' payload) — 8-bit input drained
  as 5-bit groups, MSB-first. Ported from `kotobase.cid/base32-lower-no-pad`."
  [^bytes data]
  (let [sb (StringBuilder.)
        {:keys [bits value]}
        (reduce
         (fn [{:keys [bits value]} b]
           (let [b (bit-and (int b) 0xff)
                 value (bit-or (bit-shift-left value 8) b)
                 bits (+ bits 8)]
             (loop [bits bits value value]
               (if (>= bits 5)
                 (do (.append sb (.charAt b32 (bit-and (unsigned-bit-shift-right value (- bits 5)) 31)))
                     (recur (- bits 5) value))
                 {:bits bits :value value}))))
         {:bits 0 :value 0}
         data)]
    (when (pos? bits)
      (.append sb (.charAt b32 (bit-and (bit-shift-left value (- 5 bits)) 31))))
    (.toString sb)))

(defn graph-cid-from-name
  "KotobaCid::from_bytes(name).to_multibase(): SHA-256(name) behind a
  CIDv1/dag-cbor/sha2-256 header (0x01 0x71 0x12 0x20), base32-lower 'b'."
  [^String name]
  (let [hash (sha256 (.getBytes name "UTF-8"))
        cid  (byte-array (concat [(unchecked-byte 0x01) (unchecked-byte 0x71)
                                   (unchecked-byte 0x12) (unchecked-byte 0x20)]
                                  (seq hash)))]
    (str "b" (base32-lower-no-pad cid))))

(defn canonical-graph
  "The deterministic graph CID for one of kouhou's databases. Ported from
  `kotobase.cid/canonical-graph`."
  [did db-name]
  (graph-cid-from-name (str "kotobase/db/" did "/" db-name)))

(def default-db-name
  "kouhou's primary database — the briefing ledger."
  "publicinfo")

(defn generate-identity
  "A fresh Ed25519 identity {:private-key :public-key :did :graph}."
  []
  (let [kp (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))
        pub (.getPublic kp)
        did (did-key pub)]
    {:private-key (.getPrivate kp) :public-key pub :did did
     :graph (canonical-graph did default-db-name)
     :private-b64 (.encodeToString (Base64/getEncoder) (.getEncoded (.getPrivate kp)))
     :public-b64  (.encodeToString (Base64/getEncoder) (.getEncoded pub))}))

(defn load-identity
  "Reload a persisted identity from base64 PKCS8 private + X.509 public."
  [{:keys [private-b64 public-b64]}]
  (let [kf (KeyFactory/getInstance "Ed25519")
        priv (.generatePrivate kf (PKCS8EncodedKeySpec. (.decode (Base64/getDecoder) private-b64)))
        pub  (.generatePublic kf (X509EncodedKeySpec. (.decode (Base64/getDecoder) public-b64)))
        did  (did-key pub)]
    {:private-key priv :public-key pub :did did
     :graph (canonical-graph did default-db-name)
     :private-b64 private-b64 :public-b64 public-b64}))

(defn load-or-create-identity!
  "Per-actor key: load kouhou's persisted Ed25519 identity at `path`, or
  generate + persist one on first run (only the b64 key material is stored)."
  [path]
  (let [f (java.io.File. ^String path)]
    (if (.exists f)
      (load-identity (edn/read-string (slurp f)))
      (let [id (generate-identity)
            parent (.getParentFile (.getAbsoluteFile f))]
        (when parent (.mkdirs parent))
        (spit f (pr-str (select-keys id [:private-b64 :public-b64])))
        id))))

(defn- ed-sign ^bytes [priv ^bytes msg]
  (let [s (doto (Signature/getInstance "Ed25519") (.initSign priv))] (.update s msg) (.sign s)))

(defn verify? [pub ^bytes msg ^bytes sig]
  (let [v (doto (Signature/getInstance "Ed25519") (.initVerify pub))] (.update v msg) (.verify v sig)))

(defn mint
  "Mint a base64 cacao_b64 the agent signs itself.
   identity: {:private-key :public-key :did}
   grant:    {:cap :cap/read|:cap/transact|:cap/admin :scope <graph>}
   opts:     {:aud <server did/uri> :nonce :issued-at :expiry}"
  [{:keys [private-key did]} grant {:keys [aud nonce issued-at expiry]}]
  (let [payload (grant->payload grant {:iss did :aud aud :nonce nonce
                                       :issued-at issued-at :expiry expiry})
        msg     (siwe-message payload)
        sig     (ed-sign private-key (.getBytes ^String msg "UTF-8"))
        sig-b64 (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) sig)
        wire    (->wire payload sig-b64)]
    (.encodeToString (Base64/getEncoder) (cbor-bytes wire))))
