(ns mcp.server
  "Pure Clojure MCP Server implementation using stdin/stdout."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

(defn- map->json [m]
  (json/write-str m))

(defn- json->map [s]
  (try
    (json/read-str s :key-fn keyword)
    (catch Exception _ nil)))

(defn- jsonrpc-response [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- jsonrpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- clean-tool-schema
  "Removes the Clojure handler function so it can be serialized to JSON."
  [tool]
  (-> tool
      (dissoc :handler)
      (assoc :inputSchema (or (:inputSchema tool) {:type "object" :properties {}}))))

(defn- handle-initialize [server req]
  (let [id (:id req)
        params (:params req)
        result {:protocolVersion "2026-07-24"
                :capabilities {:tools {}}
                :serverInfo {:name (:name server) :version (:version server)}}]
    (jsonrpc-response id result)))

(defn- handle-tools-list [server req]
  (let [id (:id req)
        tools (map clean-tool-schema (:tools server))]
    (jsonrpc-response id {:tools tools})))

(defn- handle-tools-call [server req]
  (let [id (:id req)
        params (:params req)
        tool-name (:name params)
        args (:arguments params)
        tool (first (filter #(= (:name %) tool-name) (:tools server)))]
    (if-not tool
      (jsonrpc-error id -32601 (str "Tool not found: " tool-name))
      (try
        (let [result ((:handler tool) args)
              ;; MCP expects the result wrapped in a content block
              content {:content [{:type "text" :text (str result)}]}]
          (jsonrpc-response id content))
        (catch Exception e
          (let [error-content {:isError true
                               :content [{:type "text" :text (.getMessage e)}]}]
            (jsonrpc-response id error-content)))))))

(defn- route-request [server req]
  (let [method (:method req)]
    (cond
      (= method "initialize") (handle-initialize server req)
      (= method "notifications/initialized") nil ; Notifications get no response
      (= method "tools/list") (handle-tools-list server req)
      (= method "tools/call") (handle-tools-call server req)
      :else (jsonrpc-error (:id req) -32601 (str "Method not found: " method)))))

(defn run-server
  "Starts the MCP server, listening on stdin and writing to stdout.
   Expects a server definition map containing :name, :version, and :tools."
  [server]
  (let [reader (BufferedReader. (InputStreamReader. System/in))]
    (loop [line (.readLine reader)]
      (when line
        (when-not (str/blank? line)
          (when-let [req (json->map line)]
            (try
              (when-let [resp (route-request server req)]
                (println (map->json resp))
                (flush))
              (catch Exception e
                (println (map->json (jsonrpc-error (:id req) -32603 (str "Internal error: " (.getMessage e)))))
                (flush)))))
        (recur (.readLine reader))))))