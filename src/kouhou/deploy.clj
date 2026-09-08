(ns kouhou.deploy
  "Deploy entrypoint — wires a REAL Murakumo-fleet LLM (langchain.model
  OpenAI-compatible against the local Ollama, gemma-4-E4B) into the kouhou
  organizer and runs ONE source digest end-to-end.

  Publication is MockPublisher by default: the autonomous-publish rail is live
  (ADR-2606281500), but an actual aozora write needs (a) the actor's did
  registered on the PDS, (b) a member CACAO leash (LEASH env — the off-switch),
  and (c) a real Publisher wired via `kouhou.aozora`. That flip is the owner's.
  This entrypoint proves real-LLM → PublicInfoGovernor → (mock) publish against
  the live Murakumo model.

  Usage: clojure -M:dev -m kouhou.deploy \"<title>\" \"<url>\" \"<raw text>\"
  Env:   KOUHOU_OLLAMA_URL (default http://127.0.0.1:11434)
         KOUHOU_OLLAMA_MODEL (default gemma-4-E4B qat)"
  (:require [kotoba.net.jvm-host :as jvm-host]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [langchain.model :as model]
            [langgraph.graph :as g]
            [kouhou.advisor :as advisor]
            [kouhou.aozora :as aozora]
            [kouhou.cacao :as cacao]
            [kouhou.publisher :as publisher]
            [kouhou.store :as store]
            [kouhou.operation :as op])
  (:import [java.net URI]
           (java.time Duration))
  (:gen-class))

(def ^:private default-ollama-url
  (or (System/getenv "KOUHOU_OLLAMA_URL") "http://127.0.0.1:11434"))

(def ^:private default-ollama-model
  (or (System/getenv "KOUHOU_OLLAMA_MODEL")
      "hf.co/unsloth/gemma-4-E4B-it-qat-GGUF:UD-Q4_K_XL"))

(defn jvm-http-fn
  "Delegated to kotoba.net.jvm-host (the workspace's single java.net.http site)."
  [{:keys [url method headers body]}]
  ((jvm-host/http-transport {:timeout-seconds 120})
   {:url url :method (or method :post) :headers headers :body body}))

(defn ollama-chat-model
  "Build a langchain.model/openai-model against a Murakumo-fleet Ollama."
  ([]
   (ollama-chat-model default-ollama-url default-ollama-model))
  ([ollama-url ollama-model]
   (advisor/assert-murakumo! ollama-url)
   (model/openai-model
    {:url        (str ollama-url "/v1/chat/completions")
     :model      ollama-model
     :api-key    nil
     :http-fn    jvm-http-fn
     :json-write json/write-str
     :json-read  #(json/read-str % :key-fn keyword)})))

(defn identify-live
  "Live identify test (ADR-2607022300 identify facet): generate the actor's
  self-sovereign did:key, then createSession(self-CACAO)→JWT→createRecord a
  profile record to pds.aozora.app. Proves the app-aozora-pds auth flow.
  clojure -M:dev -m kouhou.deploy identify-live"
  []
  (let [id  (cacao/load-or-create-identity! ".kouhou/identity.edn")
        pub (aozora/aozora-publisher {:pds        "https://pds.aozora.app"
                                      :identity   id
                                      :json-write json/write-str
                                      :json-read  json/read-str})
        profile {:$type       "com.etzhayyim.apps.kouhou.publicBriefing.profile"
                 :collection  "com.etzhayyim.apps.kouhou.publicBriefing.profile"
                 :rkey        "self"
                 :displayName "広報 — Public-Interest Info Curator"
                 :description "kouhou (広報) live identify via createSession→createRecord (self-sovereign did:key)."
                 :lexicons    ["com.etzhayyim.apps.kouhou.publicBriefing"]}]
    (println "actor did:key :" (:did id))
    (println "createSession→createRecord profile @ pds.aozora.app, repo=" (:did id))
    (try
      (let [r (publisher/publish! pub profile)] (println "PUBLISHED:" r))
      (catch Exception e
        (println "FAILED:" (ex-message e) (pr-str (ex-data e)))))))

(defn -main
  [& args]
  (when (= (first args) "identify-live") (identify-live) (System/exit 0))
  (let [[title url raw] (if (seq args) args
                            ["令和8年度 衛生対策基本方針（例）"
                             "https://press.example.go.jp/2026/0702"
                             "政府は本日、令和8年度の衛生対策基本方針を公表した。主な柱は予防接種の普及、保健所機能の強化、感染症サーベイランスの拡充である。"])
        chat    (ollama-chat-model)
        ;; 512 is too tight for a "thinking" model (gemma4:e4b-it-qat emits a
        ;; separate :reasoning field before :content and can burn the whole
        ;; budget there) -- verified live 2026-07-13 against yosoku.deploy.
        adv     (advisor/llm-advisor chat {:max-tokens 1024})
        s       (store/seed-db)
        pub     (publisher/mock-publisher)
        actor   (op/build s {:advisor adv :publisher pub})
        sid     "deploy-1"
        req     {:op :source/digest :source-id sid :url url
                 :title title :raw raw}
        r       (g/run* actor {:request req :context {:actor-id "kouhou" :phase 1}}
                        {:thread-id sid})]
    (println "=== kouhou deploy (real LLM @ Murakumo) ===")
    (println "source     :" url)
    (println "disposition:" (get-in r [:state :disposition]))
    (println "briefing   :" (pr-str (get (store/briefing s sid) :summary)))
    (println "published? :" (boolean (get-in r [:state :published])) "(mock publisher)")
    (println "ledger tail:" (pr-str (last (store/ledger s))))))