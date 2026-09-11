(ns kouhou.advisor
  "organizer — the *contained intelligence node* for kouhou. It takes a fetched
  source item (url + title + raw text, from a registered public-sector /
  public-interest source) and returns a PROPOSAL: one curated briefing (a
  faithful summary + provenance URL + domain/tags), with a confidence. It NEVER
  returns a committed record and NEVER decides publication — the
  PublicInfoGovernor censors every proposal downstream, and only :commit writes
  the SSoT + publishes. Mirrors the `Advisor` protocol shape used by
  tashikame.advisor / tsumugu.mangallm.

  Sealed by construction: the default `mock-advisor` is deterministic (no
  non-deterministic free-write). The real advisor wires `langchain.model`
  against the Murakumo fleet (DEFAULT-PREFERRED per Rider v3.3 §2(i)) — still
  proposal-only, still governor-censored. Aggregate-first: one run = one
  briefing per source digest (not a flood).

  Proposal shape:
    {:summary    str
     :rationale  str
     :briefings  [{:title :summary :source-url :domain :tags :confidence}]
     :effect     :assessment   ; kouhou only ever curates, never actuates
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]))

(defprotocol Advisor
  (-organize [advisor store request] "store + request → proposal map"))

(defn- summarize [raw]
  ;; R0 mock: first ~60 chars of the raw text, ellipsized. Real advisor uses an
  ;; LLM summarizer (faithful, no embellishment — governor checks provenance).
  (let [s (str/trim (str raw ""))]
    (if (str/blank? s) "(要約なし)"
        (str (subs s 0 (min (count s) 60)) (when (> (count s) 60) "…")))))

(defn- organize* [{:keys [url title raw source-id]}]
  (cond
    (str/blank? url)
    {:summary "no source url" :rationale "no provenance" :briefings []
     :effect :noop :confidence 0.0}

    :else
    {:summary (str "curated briefing for " (or source-id url))
     :rationale "mock organizer: faithful summary + provenance"
     :briefings [{:title      (or (str/trim (str title "")) "(無題)")
                  :summary    (summarize raw)
                  :source-url url
                  :domain     :general
                  :tags       []
                  :confidence 0.8}]
     :effect :assessment :confidence 0.8}))

(defn mock-advisor
  "The deterministic organizer (default everywhere — no non-deterministic LLM
  free-write). Real-LLM wiring is a swap via `langchain.model` on Murakumo."
  []
  (reify Advisor (-organize [_ _store req] (organize* req))))

(defn trace
  "Decision-grounded audit record (evaluation appeals, publish audits)."
  [request proposal]
  {:t          :organizer-proposal
   :op         (:op request)
   :source-id  (:source-id request)
   :summary    (:summary proposal)
   :briefings  (:briefings proposal)
   :confidence (:confidence proposal)})

;; ───────────────────────── real-LLM advisor (Murakumo fleet) ─────────────────────────
;; Sealed like the mock: returns a PROPOSAL only — the PublicInfoGovernor still
;; censors. The model is an INJECTED langchain.model/ChatModel (OpenAI-compatible
;; Ollama / LiteLLM, or Anthropic). Inference is DEFAULT-PREFERRED on Murakumo
;; (Rider v3.3 §2(i)); the deploy entrypoint builds it against the local Ollama
;; (gemma-4-E4B) and injects it. Faithful summarization only — the governor
;; still checks the source is in the public-sector registry.

(def allowed-infer-hosts
  "Murakumo-fleet inference hosts only (Rider v3.3 §2(i)). No opaque/lock-in
  commercial GPU by default. The com-junkawasaki tailnet entries (added
  2026-07-13) are the same physical fleet nodes as the localhost/LAN
  entries above, reachable via Tailscale instead — confirmed live and
  cross-checked against the fleet's own registry
  (https://api.murakumo.cloud/nodes)."
  #{"127.0.0.1:11434" "localhost:11434"
    "127.0.0.1:4000"  "localhost:4000"
    "192.168.1.70:4000"
    ;; com-junkawasaki tailnet Murakumo-fleet Ollama nodes (verified live
    ;; 2026-07-13 against https://api.murakumo.cloud/nodes)
    "100.98.142.59:11434"   ; dan
    "100.66.28.79:11434"    ; zebulun
    "100.102.78.81:11434"   ; levi
    "100.75.169.8:11434"    ; benjamin
    "100.89.204.30:11434"   ; issachar
    "100.82.123.35:11434"   ; joseph
    "100.101.27.85:11434"   ; naphtali
    "100.81.66.86:11434"})  ; simeon

(defn- host-port [url]
  (when (string? url) (second (re-find #"(?i)^[a-z]+://([^/]+)" url))))

(defn assert-murakumo!
  "Throw if `ollama-url` is not a Murakumo-fleet inference host."
  [ollama-url]
  (let [hp (host-port ollama-url)]
    (when-not (contains? allowed-infer-hosts hp)
      (throw (ex-info (str "inference host " hp " is not Murakumo-fleet (Rider §2(i))")
                      {:host hp})))))

(def kouhou-system-prompt
  "You are kouhou (広報), a public-interest / public-sector information curator.
Given a source URL + title + raw text, produce a FAITHFUL summary — do NOT
embellish, invent, or add facts beyond the source. Respond with ONLY a
single-line EDN map, no prose, no code fences:
  {:title \"...\" :summary \"...\" :source-url \"https://...\" :domain <general|health|disaster|econ|social-security|education|environment> :tags [\"...\"] :confidence <0.0-1.0>}
The :source-url MUST be the URL given to you.")

(defn- build-prompt [{:keys [url title raw]}]
  (str "Source URL: " url "\n"
       "Title: " (or title "") "\n"
       "Raw text:\n" (or raw "") "\n\n"
       "Return ONLY the EDN map now."))

(defn parse-briefing-edn
  "Defensively parse the LLM's EDN briefing map. Any parse failure → a safe
  briefing that still carries the original source-url (so the PublicInfoGovernor
  can gate it on the registry)."
  [content url]
  (let [s (-> (str content)
              (str/replace #"(?s)```[a-zA-Z]*" "")
              (str/replace "```" ""))]
    (try
      (let [m (some-> (re-find #"(?s)\{.*\}" s) edn/read-string)
            conf (:confidence m)]
        {:title      (str (or (:title m) ""))
         :summary    (str (or (:summary m) ""))
         :source-url (str (or (:source-url m) url ""))
         :domain     (or (:domain m) :general)
         :tags       (vec (filter string? (:tags m)))
         :confidence (if (number? conf) (max 0.0 (min 1.0 (double conf))) 0.3)})
      (catch #?(:clj Throwable :cljs :default) _
        {:title "" :summary "" :source-url (str url)
         :domain :general :tags [] :confidence 0.2}))))

(defn llm-advisor
  "Organizer backed by a langchain.model/ChatModel (OpenAI-compatible Ollama /
   LiteLLM, or Anthropic). Sealed: returns a PROPOSAL only; the
   PublicInfoGovernor still censors. gen-opts → model/-generate opts."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-organize [_ _store request]
       (let [url     (:url request)
             content (:content
                      (model/-generate chat-model
                        [{:role :system :content kouhou-system-prompt}
                         {:role :user   :content (build-prompt request)}]
                        gen-opts)
                      {})
             b       (parse-briefing-edn content url)]
         {:summary    (str "kouhou briefing: " (:title b))
          :rationale  "LLM faithful summary (Murakumo); governor-censored"
          :briefings  [b]
          :effect     :assessment
          :confidence (:confidence b)})))))
