(ns kouhou.public-briefing-contract-test
  "The canonical EDN Lexicon follows the executable publication boundary."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [kouhou.publisher :as publisher]))

(defn- contract []
  (edn/read-string (slurp "lex/publicBriefing.edn")))

(deftest canonical-contract-identifies-the-published-collection
  (is (= publisher/collection (:id (contract)))))

(deftest canonical-contract-covers-the-executable-record-boundary
  (testing "operation fields plus aozora-assigned actor and createdAt are explicit"
    (let [record (get-in (contract) [:defs :main :record])
          expected #{"sourceId" "actor" "title" "summary" "sourceUrl"
                     "domain" "tags" "text" "createdAt"}]
      (is (= expected (set (:required record))))
      (is (= expected (set (map name (keys (:properties record))))))
      (is (= "did" (get-in record [:properties :actor :format])))
      (is (= "uri" (get-in record [:properties :sourceUrl :format])))
      (is (= "datetime" (get-in record [:properties :createdAt :format]))))))
