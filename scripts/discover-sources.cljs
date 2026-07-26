#!/usr/bin/env nbb
;; scripts/discover-sources.cljs — kouhou 広報 — 政府公式サイトから公報 feed を発見し、
;; `registry/sources.seed.json` を実測で育てる（com-junkawasaki/root ADR-2607253400）。
;;
;;   nbb scripts/discover-sources.cljs                       # 既存 registry を再測定
;;   nbb scripts/discover-sources.cljs --candidates <f.json> # 候補を足して発見を試みる
;;   nbb scripts/discover-sources.cljs --apply               # 結果を registry に書き戻す
;;
;; ## なぜこれが要るか
;;
;; kouhou は「政府が何を発表したか」= **1次ソース**を扱う唯一の actor だが、registry は
;; 8 source（JP US GB DE FR EU INT）で止まっていた。ooyake が 197 の jurisdiction の
;; 政府組織を official-url つきで持っているのに、その発表を取りに行ける先は 7 つしか
;; 無い、という非対称があった。kawaraban（2次情報＝報道）は 55 カ国に届いているので、
;; **1次情報の層だけが薄い**という状態。
;;
;; 候補は ooyake の `:gov.unit/official-url`（内閣・政府ポータル）から作る。人が国名を
;; 並べて URL を推測するのではなく、既に maintainer-verified された政府組織台帳を
;; そのまま候補源にする——これで「どの国を試したか」が台帳と一致する。
;;
;; ## 発見の作法（kawaraban の verify-feeds.cljs と同一）
;;
;; homepage の `<link rel="alternate" type="application/rss+xml">` を読み、無ければ
;; よくあるパスを順に試し、**実際に item を返した URL だけ**を採る。判定規則は
;; `src/kouhou/live_fetch.cljc` の `parse-feed` と揃える（<rss / <rdf:RDF なら <item>、
;; <feed> なら <entry>）——ここで 0 件なら本番の ingest でも 0 件になる。
;;
;; **403 を返すサイトは未達として記録し、回避しない。** ブラウザの User-Agent を騙って
;; bot 検出を抜けることはしない。政府サイトは報道機関より遮断が多く、カバレッジ数を
;; 上げたい誘惑が強いが、それは測定ではなく偽装になる。
;;
;; ## charter 上の位置づけ
;;
;; これは ingest ではない。digest を1件も作らず、保存せず、publish しない
;; （`KOUHOU_ALLOW_LIVE_INGEST` は関与しない）。やっているのは「この URL は feed か」の
;; 到達性確認だけで、registry の `verified` フィールドが元々表していた主張を、人の記憶
;; ではなく実測に固定する。PublicInfoGovernor の判定には一切触れない。

(require '[clojure.string :as str]
         '["node:fs" :as fs])

(def argv (vec *command-line-args*))
(def flags (set (filter #(str/starts-with? % "--") argv)))
(def apply? (contains? flags "--apply"))
(def candidates-path
  (when-let [i (first (keep-indexed #(when (= "--candidates" %2) %1) argv))]
    (nth argv (inc i) nil)))
(def registry-path (or (.. js/process -env -KOUHOU_REGISTRY_PATH) "registry/sources.seed.json"))
(def timeout-ms (js/parseInt (or (.. js/process -env -KOUHOU_DISCOVER_TIMEOUT_MS) "10000") 10))
(def concurrency 8)

(def user-agent
  "live_fetch.cljc の実 fetch と同じ名乗り。検証と本番で別 UA を使うと、UA で弾く
  サイトを検証だけ通してしまう。"
  "kouhou/1.0 (+https://github.com/etzhayyim/com-etzhayyim-kouhou; public-information curator, government press/gazette only)")

(def common-feed-paths
  ["/rss" "/feed" "/feed/" "/rss.xml" "/feed.xml" "/index.xml" "/atom.xml"
   "/en/rss" "/en/feed" "/news/rss" "/news/feed" "/press/rss" "/rss/news"
   "/actualites/rss" "/rss/index.xml" "/rssfeed"])

(defn- count-matches [re s] (count (re-seq re s)))

(defn classify [body]
  (cond
    (re-find #"(?i)<rss[\s>]" body)     {:kind "rss"  :items (count-matches #"(?i)<item[\s>]" body)}
    (re-find #"(?i)<rdf:RDF[\s>]" body) {:kind "rdf"  :items (count-matches #"(?i)<item[\s>]" body)}
    (re-find #"(?i)<feed[\s>]" body)    {:kind "atom" :items (count-matches #"(?i)<entry[\s>]" body)}
    (re-find #"(?i)<html[\s>]" body)    {:kind "html" :items 0}
    :else                                {:kind "unknown" :items 0}))

(defn fetch-text [url]
  (if (str/blank? (str url))
    (js/Promise.resolve {:error "no URL"})
    (let [c (js/AbortController.)
          t (js/setTimeout #(.abort c) timeout-ms)]
      (-> (js/fetch url #js {:signal (.-signal c) :redirect "follow"
                             :headers #js {"User-Agent" user-agent
                                           "Accept" "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html, */*"}})
          (.then (fn [resp] (.then (.text resp) (fn [body] {:status (.-status resp) :body body}))))
          (.catch (fn [e] {:error (or (.-message e) (str e))}))
          (.finally (fn [] (js/clearTimeout t)))))))

(defn probe [url]
  (-> (fetch-text url)
      (.then (fn [{:keys [status body error]}]
               (if error
                 {:url url :status 0 :kind "error" :items 0 :ok false :error error}
                 (let [{:keys [kind items]} (classify body)]
                   {:url url :status status :kind kind :items items
                    :ok (and (= 200 status) (contains? #{"rss" "rdf" "atom"} kind) (pos? items))}))))))

(defn- absolutize [base href]
  (try (.-href (js/URL. href base)) (catch :default _ nil)))

(defn declared-feed-urls [base html]
  (->> (re-seq #"(?i)<link\s[^>]*>" html)
       (filter #(re-find #"(?i)type=\"?application/(rss|atom)\+xml" %))
       (keep (fn [tag] (when-let [m (re-find #"(?i)href=\"([^\"]+)\"" tag)]
                         (absolutize base (second m)))))
       distinct (take 6) vec))

(defn- sequential-probe [urls]
  (letfn [(step [remaining last-result]
            (if (empty? remaining)
              (js/Promise.resolve last-result)
              (-> (probe (first remaining))
                  (.then (fn [r] (if (:ok r)
                                   (js/Promise.resolve r)
                                   (step (rest remaining) r)))))))]
    (if (empty? urls)
      (js/Promise.resolve {:url nil :status 0 :kind "error" :items 0 :ok false :error "no candidate URL"})
      (step (vec urls) nil))))

(defn discover [homepage]
  (-> (fetch-text homepage)
      (.then (fn [{:keys [body error]}]
               (let [declared (if (or error (str/blank? (str body))) [] (declared-feed-urls homepage body))
                     guessed (keep #(absolutize homepage %) common-feed-paths)]
                 (sequential-probe (distinct (concat declared guessed))))))))

(defn check-one [{:keys [url homepage source-id] :as entry}]
  (-> (probe url)
      (.then (fn [r]
               (if (or (:ok r) (str/blank? (str (or homepage url))))
                 (js/Promise.resolve (assoc r :source-id source-id :entry entry))
                 (-> (discover (or homepage url))
                     (.then (fn [d] (assoc d :source-id source-id :entry entry
                                           :discovered (boolean (:ok d)))))))))))

(defn check-batch [entries]
  (letfn [(step [acc remaining]
            (if (empty? remaining)
              (js/Promise.resolve acc)
              (let [[b r] (split-at concurrency remaining)]
                (-> (js/Promise.all (clj->js (map check-one b)))
                    (.then (fn [rs]
                             (let [rs (js->clj rs :keywordize-keys true)]
                               (doseq [x rs]
                                 (println (str (if (:ok x) "  ok  " "  FAIL")
                                               " " (:source-id x)
                                               " status=" (:status x) " kind=" (:kind x)
                                               " items=" (:items x)
                                               (when (:discovered x) (str " discovered=" (:url x)))
                                               (when (:error x) (str " error=" (:error x))))))
                               (step (into acc rs) r))))))))]
    (step [] (vec entries))))

(defn today [] (subs (.toISOString (js/Date.)) 0 10))

(defn ->registry-entry
  "測定結果 → registry の1エントリ。`read-only` は kouhou の既存 registry の不変条件
  （取りに行くだけで書き込まない）なので常に true。"
  [{:keys [ok status kind items url error entry discovered]}]
  (let [homepage (or (:homepage entry) (:url entry))]
    (cond-> {"source-id" (:source-id entry)
             "name" (:name entry)
             "host" (try (.-host (js/URL. (or url homepage "http://x"))) (catch :default _ ""))
             "kind" (or (:kind entry) "government-pr")
             "domain" (or (:domain entry) "general")
             "country" (:country entry)
             "url" (or (when ok url) (:url entry) homepage)
             "read-only" true
             "verified" (boolean ok)
             "comment" (str "discover-sources " (today) ": "
                            (if ok
                              (str "HTTP " status ", " kind ", " items " items."
                                   (when discovered " Feed URL discovered from the government homepage this run."))
                              (cond
                                error (str "unreachable — " error ".")
                                (not= 200 status) (str "HTTP " status " — no usable feed at this endpoint.")
                                (= "html" kind) "HTTP 200 but HTML, not RSS/Atom — no feed declared or found at the common paths."
                                :else (str "HTTP " status ", " kind ", " items " items — nothing to ingest.")))
                            (when (:ooyake-unit entry)
                              (str " Candidate derived from ooyake " (:ooyake-unit entry) "'s :gov.unit/official-url.")))}
      (:ooyake-unit entry) (assoc "ooyake-unit" (:ooyake-unit entry)))))

(defn -main []
  (let [existing (js->clj (js/JSON.parse (str (.readFileSync fs registry-path "utf8"))) :keywordize-keys true)
        cands (if candidates-path
                (js->clj (js/JSON.parse (str (.readFileSync fs candidates-path "utf8"))) :keywordize-keys true)
                [])
        by-id (into {} (map (juxt :source-id identity) existing))
        ;; 既存 entry は url を、新規候補は homepage を起点にする
        targets (concat (map #(assoc % :homepage (or (:homepage %) (:url %))) existing)
                        (remove #(contains? by-id (:source-id %)) cands))
        prev-verified (count (filter :verified existing))]
    (println (str "kouhou discover-sources — " (count existing) " registered + "
                  (- (count targets) (count existing)) " new candidate(s), timeout " timeout-ms "ms"))
    (-> (check-batch targets)
        (.then
         (fn [results]
           (let [ok-n (count (filter :ok results))
                 entries (mapv ->registry-entry results)
                 countries (count (distinct (keep #(get % "country") (filter #(get % "verified") entries))))]
             (println (str "\n" ok-n "/" (count results) " sources verified live"
                           " (was " prev-verified "/" (count existing) ")"
                           " — " countries " countries/regions with a working government feed"))
             (if-not apply?
               (println "\n(report only — pass --apply to write registry back)")
               ;; 崩壊ガード: 既存の verified がまとめて落ちたら書かない。無人実行に
               ;; 載せる前提なので、環境障害を測定値として記録させない。
               (if (and (pos? prev-verified) (< ok-n (* 0.7 prev-verified)))
                 (do (println (str "\nREFUSED to write: verified collapsed " prev-verified " → " ok-n
                                   " (<70% retained). That is an environment failure far more often"
                                   " than that many government feeds dying at once."))
                     (.exit js/process 1))
                 (do (.writeFileSync fs registry-path
                                     (str (js/JSON.stringify (clj->js (vec (sort-by #(get % "source-id") entries))) nil 2) "\n"))
                     (println (str "wrote " registry-path)))))))))))

(-main)
