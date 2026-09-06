(ns mcp.client
  "High-level MCP Client API."
  (:require [mcp.transport :as transport]
            [mcp.protocol :as protocol]
            [mcp.stdio]
            [mcp.http]))

(defrecord MCPClient [transport id-counter])

(defn connect
  "Initializes the MCP client. 
   transport-type: :stdio or :http
   target: vector of args for stdio, or URL string for http.
   opts: map of options (e.g., {:client-name \"my-app\" :client-version \"1.0.0\"})"
  [transport-type target opts]
  (let [transport (case transport-type
                    :stdio (mcp.stdio/create-stdio-transport target opts)
                    :http (mcp.http/create-http-transport target))
        client (->MCPClient transport (atom 0))
        capabilities {}] ; Define client capabilities here if needed

    ;; Send initialize request
    (let [init-req (protocol/initialize-request
                    (swap! (:id-counter client) inc)
                    (:client-name opts "mcp.clj")
                    (:client-version opts "0.1.0")
                    capabilities)
          response @(transport/send! transport init-req)]
      (when (:error response)
        (throw (ex-info "Failed to initialize MCP server" response)))

      ;; Send initialized notification
      (transport/send! transport (protocol/initialized-notification)))
    client))

(defn list-tools
  "Fetches the list of available tools from the MCP server."
  [client]
  (let [id (swap! (:id-counter client) inc)
        req (protocol/list-tools-request id)
        response @(transport/send! (:transport client) req)]
    (if (:error response)
      (throw (ex-info "Failed to list tools" response))
      (get-in response [:result :tools] []))))

(defn call-tool
  "Calls a specific tool by name with the provided arguments map."
  [client tool-name arguments]
  (let [id (swap! (:id-counter client) inc)
        req (protocol/call-tool-request id tool-name arguments)
        response @(transport/send! (:transport client) req)]
    (if (:error response)
      (throw (ex-info "Tool execution failed" response))
      (:result response))))

(defn disconnect
  "Closes the connection to the MCP server."
  [client]
  (transport/close! (:transport client)))