(ns time-server.core
  (:require [mcp.server :refer [run-server]])
  (:import [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(defn get-current-utc-time []
  (.toString (Instant/now)))

(defn get-current-local-time []
  (.format (ZonedDateTime/now (ZoneId/systemDefault))
           DateTimeFormatter/ISO_ZONED_DATE_TIME))

(def time-server
  {:name "TimeServer"
   :version "1.0.0"
   :tools [{:name "get_current_utc_time"
            :description "Returns current UTC time in ISO 8601 format."
            :inputSchema {:type "object" :properties {} :required []}
            :handler (fn [_args] (get-current-utc-time))}

           {:name "get_current_local_time"
            :description "Returns current local system time in ISO 8601 format."
            :inputSchema {:type "object" :properties {} :required []}
            :handler (fn [_args] (get-current-local-time))}]})

(defn -main [& _args]
  (run-server time-server))