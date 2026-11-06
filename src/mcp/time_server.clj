(ns mcp.time-server
  "Small example server for local MCP tool-calling runtimes."
  (:require [mcp.server :as server])
  (:import (java.time Instant ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def example-server
  (server/create-server
   {:name "time-server"
    :version "1.0.0"
    :tools
    [{:name "get_current_utc_time"
      :description "Return the current UTC time in ISO 8601 format."
      :inputSchema {:type "object"
                    :properties {}
                    :additionalProperties false}
      :handler (fn [_arguments _context]
                 {:structuredContent {:iso (.toString (Instant/now))}})}
     {:name "get_current_local_time"
      :description "Return the current local time with its IANA time zone."
      :inputSchema {:type "object"
                    :properties {}
                    :additionalProperties false}
      :handler (fn [_arguments _context]
                 {:structuredContent
                  {:iso (.format (ZonedDateTime/now (ZoneId/systemDefault))
                                 DateTimeFormatter/ISO_ZONED_DATE_TIME)}})}]}))

(defn -main [& args]
  (case (first args)
    "http" (let [port (if-let [p (second args)] (Long/parseLong p) 3000)
                 {:keys [stop]} (server/run-http example-server {:port port})]
             (binding [*out* *err*]
               (println "Time MCP server listening on 127.0.0.1:" port))
             (.addShutdownHook (Runtime/getRuntime)
                               (Thread. ^Runnable stop))
             @(promise))
    nil (server/run-stdio example-server)
    (throw (ex-info "Usage: clojure -M:time-server [http [port]]" {}))))
