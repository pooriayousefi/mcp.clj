(ns mcp.http
  "StreamableHTTP transport for MCP (Protocol Version 2026-07-24).
   Uses single-endpoint POST requests and manages Mcp-Session-Id."
  (:require [mcp.protocol :as protocol]
            [mcp.transport :as transport]
            [clj-http.client :as client]
            [clojure.string :as str]))

(defrecord StreamableHTTPTransport [url session-id]
  transport/MCPTransport
  (send! [this message]
    (let [json-str (protocol/map->json message)
          headers (cond-> {"Content-Type" "application/json"
                           "Accept" "application/json, text/event-stream"}
                    @session-id (assoc "Mcp-Session-Id" @session-id))
          resp (try
                 (client/post url {:headers headers :body json-str :as :text})
                 (catch Exception e
                   {:status 500 :body (.getMessage e)}))
          status (:status resp)
          body (:body resp)
          content-type (get-in resp [:headers "Content-Type"]
                               (get-in resp [:headers "content-type"]))]

      ;; Capture session ID from initialize response
      (when (and (= 200 status)
                 (nil? @session-id)
                 (get-in resp [:headers "Mcp-Session-Id"]))
        (reset! session-id (get-in resp [:headers "Mcp-Session-Id"])))

      ;; Deliver a simulated promise matching the interface
      (let [p (promise)]
        (cond
          ;; Standard JSON response
          (and (= 200 status) (re-find #"application/json" content-type))
          (deliver p (protocol/json->map body))

          ;; Server-Sent Events response (StreamableHTTP can still use SSE for streaming)
          (and (= 200 status) (re-find #"text/event-stream" content-type))
          (let [lines (clojure.string/split-lines body)
                json-lines (map #(clojure.string/replace % #"^data: " "") lines)
                parsed (filterv some? (map protocol/json->map json-lines))
                ;; Return the last message that matches our ID, or the first one
                response (or (first (filter #(= (:id %) (:id message)) parsed))
                             (first parsed))]
            (deliver p response))

          ;; Error
          :else
          (deliver p {:jsonrpc "2.0" :id (:id message)
                      :error {:code status :message (str "HTTP Error: " body)}}))
        p)))

  (close! [this]
    ;; In StreamableHTTP, we optionally send a DELETE to terminate the session
    (when @session-id
      (try
        (client/delete url {:headers {"Mcp-Session-Id" @session-id}})
        (catch Exception _)))
    (reset! session-id nil)))

(defn create-http-transport
  "Creates an HTTP transport for a StreamableHTTP MCP server.
   e.g., (create-http-transport \"http://localhost:3000/mcp\")"
  [url]
  (->StreamableHTTPTransport url (atom nil)))