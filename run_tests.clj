(ns kouhou.run-tests
  "Test runner for com-etzhayyim-kouhou (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path: `clojure -M:dev:test`
  (cognitect test-runner). This runner: `clojure -M -m kouhou.run-tests`."
  (:require [clojure.test :refer [run-tests]]
            [kouhou.governor-contract-test]
            [kouhou.store-contract-test]
            [kouhou.edn-store-test]
            [kouhou.raw-archive-test]
            [kouhou.operation-test])
  (:gen-class))

(defn -main [& _args]
  (let [res (run-tests
             'kouhou.governor-contract-test
             'kouhou.store-contract-test
             'kouhou.edn-store-test
             'kouhou.raw-archive-test
             'kouhou.operation-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))
