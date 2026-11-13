(ns mcp.test-runner
  (:require [clojure.test :as test]
            [mcp.core-test]))

(defn -main [& _]
  (let [result (test/run-tests 'mcp.core-test)
        exit-code (if (pos? (+ (:fail result) (:error result))) 1 0)]
    ;; http-kit owns background event-loop threads; this CLI runner must exit
    ;; deterministically after the test suite has completed.
    (shutdown-agents)
    (System/exit exit-code)))
