(ns mcp.transport)

(defprotocol MCPTransport
  "Abstract transport layer for MCP communication."
  (send! [this message]
    "Sends a JSON-RPC message map. Returns a promise that will deliver 
     the parsed JSON response map (or nil for notifications).")
  (close! [this]
    "Closes the transport connection and cleans up resources."))