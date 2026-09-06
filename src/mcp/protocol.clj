(ns mcp.protocol
  "Pure Clojure definitions for JSON-RPC 2.0 and MCP protocol messages."
  (:require [clojure.data.json :as json]))

(def jsonrpc-version "2.0")

(defn request
  "Builds a JSON-RPC request map."
  [id method params]
  (cond-> {:jsonrpc jsonrpc-version :id id :method method}
    (some? params) (assoc :params params)))

(defn notification
  "Builds a JSON-RPC notification map (no id)."
  [method params]
  (cond-> {:jsonrpc jsonrpc-version :method method}
    (some? params) (assoc :params params)))

(defn initialize-request
  "Builds the MCP initialize request payload."
  [id client-name client-version capabilities]
  (request id "initialize"
           {:protocolVersion "2026-07-24" ; The newest StreamableHTTP spec
            :capabilities capabilities
            :clientInfo {:name client-name :version client-version}}))

(defn initialized-notification
  "Builds the MCP initialized notification."
  []
  (notification "notifications/initialized" nil))

(defn list-tools-request
  "Builds the MCP tools/list request."
  [id]
  (request id "tools/list" nil))

(defn call-tool-request
  "Builds the MCP tools/call request."
  [id name arguments]
  (request id "tools/call" {:name name :arguments (or arguments {})}))

(defn json->map
  "Safely parse JSON string to Clojure map with keyword keys."
  [json-str]
  (try
    (json/read-str json-str :key-fn keyword)
    (catch Exception _ nil)))

(defn map->json
  "Serialize Clojure map to JSON string."
  [m]
  (json/write-str m))