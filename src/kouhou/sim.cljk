(ns kouhou.sim
  "Offline demo: drive two source digests (one registered, one unregistered)
  through the kouhou actor on a MemStore + mock organizer + mock publisher
  (no network). `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [kouhou.operation :as op]
            [kouhou.store :as store]
            [kouhou.advisor :as advisor]
            [kouhou.publisher :as publisher])
  (:gen-class))

(defn -main [& _args]
  (let [s   (store/seed-db)
        pub (publisher/mock-publisher)
        a   (op/build s {:advisor (advisor/mock-advisor) :publisher pub})]
    (doseq [src [{:op :source/digest :source-id "gov-jp-press"
                  :url "https://press.example.go.jp/2026/0702"
                  :title "令和8年度 衛生対策基本方針（例）"
                  :raw "本日、政府は令和8年度の衛生対策基本方針を公表した。主な柱は…"}
                 {:op :source/digest :source-id "anon-blog"
                  :url "https://blog.example.net/post/999"
                  :title "噂のブログ（登録外ソース）"
                  :raw "某匿名ブログの投稿。"}]]
      (let [r (g/run* a {:request src :context {:actor-id "kouhou" :phase 1}}
                      {:thread-id (:source-id src)})]
        (println (get-in r [:state :disposition]) "←" (:source-id src)
                 "published?" (some? (get-in r [:state :published])))))
    (println "--- would-be published briefings ---")
    (doseq [p @(:a pub)] (println (:source-id p) "→" (:title p) "|" (:text p)))
    (println "--- ledger ---")
    (doseq [f (store/ledger s)] (prn f))))
