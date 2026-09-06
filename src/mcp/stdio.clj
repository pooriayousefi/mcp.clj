(ns mcp.stdio
  "Stdio transport for MCP using java.lang.ProcessBuilder.
   Manages a background thread reading stdout and matching responses to requests."
  (:require [mcp.protocol :as protocol]
            [mcp.transport :as transport])
  (:import [java.io BufferedReader InputStreamReader PrintWriter]))

(defn- stdio-reader
  "Background thread reading process stdout, delivering responses to promises."
  [^Process process pending-requests notifications-callback]
  (let [reader (BufferedReader. (InputStreamReader. (.getInputStream process)))]
    (try
      (loop [line (.readLine reader)]
        (when line
          (when-let [msg (protocol/json->map line)]
            (if (:id msg)
              ;; It's a response: deliver to the matching promise
              (when-let [p (get @pending-requests (:id msg))]
                (deliver p msg)
                (swap! pending-requests dissoc (:id msg)))
              ;; It's a notification: pass to callback
              (when notifications-callback
                (notifications-callback msg))))
          (recur (.readLine reader))))
      (catch Exception _
        ;; Process died or stream closed. Deliver errors to pending requests.
        (doseq [[id p] @pending-requests]
          (deliver p {:jsonrpc "2.0" :id id :error {:code -1 :message "Stdio process terminated"}})
          (swap! pending-requests dissoc id))))))

(defrecord StdioTransport [process writer pending-requests reader-thread]
  transport/MCPTransport
  (send! [this message]
    (let [json-str (protocol/map->json message)]
      (if (:id message)
        ;; Request: create promise, store it, write to stdin, return promise
        (let [p (promise)]
          (swap! pending-requests assoc (:id message) p)
          (.println ^PrintWriter writer json-str)
          (.flush ^PrintWriter writer)
          p)
        ;; Notification: just write to stdin, return nil promise
        (do
          (.println ^PrintWriter writer json-str)
          (.flush ^PrintWriter writer)
          (promise)))))

  (close! [this]
    (when reader-thread
      (future-cancel reader-thread))
    (when writer
      (.close ^PrintWriter writer))
    (when process
      (.destroyForcibly ^Process process))))

(defn create-stdio-transport
  "Creates a StdioTransport. Expects a vector of command args.
   e.g., [\"npx\" \"-y\" \"@modelcontextprotocol/server-filesystem\" \"/tmp\"]"
  [command-args & {:keys [notifications-callback]}]
  (let [builder (ProcessBuilder. ^java.util.List command-args)
        _ (.redirectErrorStream builder true)
        process (.start builder)
        writer (PrintWriter. (.getOutputStream process))
        pending-requests (atom {})
        reader-thread (future (stdio-reader process pending-requests notifications-callback))]
    (->StdioTransport process writer pending-requests reader-thread)))