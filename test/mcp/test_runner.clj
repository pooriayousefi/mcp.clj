(ns mcp.test-runner
  (:require [clojure.test :as test]
            [mcp.core-test]))

(defn -main [& _]
  (let [result (test/run-tests 'mcp.core-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
