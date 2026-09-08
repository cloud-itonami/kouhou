(ns kouhou.aozora
  "Real app-aozora Publisher for kouhou — creates a record in the
  com.etzhayyim.apps.kouhou.publicBriefing collection on an aozora PDS via the
  AT Protocol com.atproto.repo.createRecord XRPC, authenticated by a depth-1
  self-minted CACAO (the actor's own did:key + a revocable member CACAO leash
  = the off-switch, ADR-2606281500). Ported shape from tsumugu.kotoba /
  tashikame.aozora.

  JSON pair passed by the caller, so this namespace stays dependency-free.
  Publication is the actor's own SPEECH (ADR-2606281500) — NOT actuation."
  (:require [kotoba.net.jvm-host :as jvm-host]
            [clojure.string :as str]
            [kouhou.cacao :as cacao]
            [kouhou.publisher :as publisher])
           [java.time Instant]
           [java.util UUID]))

(def default-pds "https://pds.aozora.app")

(defn jvm-http-fn
  [{:keys [url method headers body]}]
  ((jvm-host/http-transport {:timeout-seconds 120})
   {:url url :method (or method :post) :headers headers :body body}))

(defn aozora-publisher
  "Returns a `kouhou.publisher/Publisher` that creates publicBriefing records on
  the aozora PDS. opts:
    :pds         PDS base URL (default default-pds)
    :identity    {:private-key :did …} from cacao/load-or-create-identity!
    :leash       a member CACAO b64 (the revocable off-switch); nil → record
                 attributed to the actor's own did:key (depth-1 self-mint)
    :json-write  :json-read  injected JSON fns (e.g. clojure.data.json)
    :http-fn     optional override (default jvm-http-fn)"
  [{:keys [pds identity json-write json-read http-fn]
    :or   {pds default-pds http-fn jvm-http-fn}}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required (e.g. clojure.data.json/write-str)")
  (assert json-read  ":json-read fn is required (e.g. clojure.data.json/read-str)")
  (reify publisher/Publisher
    (publish! [_ record]
      ;; app-aozora-pds auth (self-sovereign CACAO, ADR-2606251700 + DEPLOY-RUNBOOK):
      ;; mint a CACAO for the actor's OWN did:key, exchange it at createSession for
      ;; an HS256 session JWT, then createRecord with that JWT — the PDS enforces
      ;; session DID == repo DID, so the repo is addressed by the actor's did:key.
      ;; (The old CACAO-Bearer-at-createRecord model returned 403 on this PDS.)
      (let [now   (str (Instant/now))
            graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
            cacao (cacao/mint identity
                              {:cap :cap/transact :scope graph}
                              {:aud pds :nonce (str (UUID/randomUUID))
                               :issued-at now
                               :expiry (str (.plusSeconds (Instant/now) 3600))})
            sess  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createSession")
                            :method  :post
                            :headers {"Content-Type" "application/json"}
                            :body    (json-write {:cacao cacao})})
            sbody (json-read (:body sess))
            jwt   (get sbody "accessJwt")]
        (when-not (and (= 200 (:status sess)) jwt)
          (throw (ex-info "aozora createSession failed"
                          {:status (:status sess) :body (:body sess)})))
        (let [coll  (or (:collection record) publisher/collection)
              rec   (-> (dissoc record :rkey :collection)
                        (assoc :createdAt now :actor (:did identity)))
              resp  (http-fn {:url     (str pds "/xrpc/com.atproto.repo.createRecord")
                              :method  :post
                              :headers {"Content-Type" "application/json"
                                        "Authorization" (str "Bearer " jwt)}
                              :body    (json-write {:repo       (:did identity)
                                                    :collection coll
                                                    :rkey       (or (:rkey record) "self")
                                                    :record     rec})})
              rbody (json-read (:body resp))]
          (when-not (= 200 (:status resp))
            (throw (ex-info "aozora createRecord failed"
                            {:status (:status resp) :body (:body resp)})))
          {:uri (get rbody "uri") :cid (get rbody "cid")})))))
