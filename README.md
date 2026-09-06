# mcp.clj

A pure, lightweight Clojure implementation of the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/).

Built with zero Java SDK bloat, `mcp.clj` relies only on `clojure.data.json`, `clj-http`, and Java's built-in `ProcessBuilder`. It provides a complete, bidirectional ecosystem for both **MCP Clients** (to consume tools) and **MCP Servers** (to expose tools) directly in idiomatic Clojure.

## Features

- **Pure Clojure:** No heavy Java dependencies. Microscopic dependency tree.
- **Bidirectional:** Includes both `mcp.client` and `mcp.server` namespaces.
- **Modern Transports:**
  - `stdio` transport (via `java.lang.ProcessBuilder`) for local subprocess communication.
  - `StreamableHTTP` transport for the newest MCP spec (`2026-07-24`), supporting both standard JSON and Server-Sent Events (SSE) streaming responses.
- **JSON-RPC 2.0 Compliant:** Handles request/response lifecycle, promises, and notifications out of the box.

## Installation

### Clojure CLI (`deps.edn`)

```clojure
{:deps {org.clojure/clojure {:mvn/version "1.12.0"}
        org.clojure/data.json {:mvn/version "2.5.0"}
        clj-http/clj-http {:mvn/version "3.13.0"}}}
```

_(Once published to Clojars or Maven Central, you can replace the local deps with `mcp.clj/mcp.clj {:mvn/version "..."}`)_

---

## Client Usage

The `mcp.client` namespace provides a high-level API for connecting to any MCP server (native stdio or remote HTTP), fetching its tool schemas, and executing tools.

### 1. Connecting via `stdio` (Local Subprocess)

This spawns a background process (e.g., an `npx` command), handles the JSON-RPC handshake, and returns a connected client map.

```clojure
(require '[mcp.client :as client])

;; Spawn the official Anthropic filesystem server as a subprocess
(def c (client/connect :stdio
                       ["npx" "-y" "@modelcontextprotocol/server-filesystem" "/tmp"]
                       {:client-name "my-clojure-app"}))
```

### 2. Connecting via `StreamableHTTP` (Remote)

For servers supporting the newest `2026-07-24` spec over HTTP.

```clojure
(def c (client/connect :http
                       "http://localhost:3000/mcp"
                       {:client-name "my-clojure-app"}))
```

### 3. Listing and Calling Tools

```clojure
;; Fetch available tool schemas (returns standard OpenAI-compatible JSON)
(client/list-tools c)

;; Call a tool by name with a map of arguments
(client/call-tool c "list_directory" {:path "/tmp"})

;; Disconnect and clean up the subprocess/connection
(client/disconnect c)
```

---

## Server Usage

The `mcp.server` namespace allows you to build standalone MCP servers in pure Clojure. This is incredibly useful for exposing Clojure-specific logic (like data transformations, database queries, or REPL operations) to LLM clients like Claude Desktop, Cursor, or your own aJent framework.

### 1. Defining a Server

Define your server as a simple Clojure map. Each tool requires a `:name`, `:description`, an `:inputSchema` (JSON Schema format), and a `:handler` function.

```clojure
(ns time-server.core
  (:require [mcp.server :refer [run-server]])
  (:import [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def time-server
  {:name "TimeServer"
   :version "1.0.0"
   :tools [
     {:name "get_current_utc_time"
      :description "Returns current UTC time in ISO 8601 format."
      :inputSchema {:type "object" :properties {} :required []}
      :handler (fn [_args] (.toString (Instant/now)))}

     {:name "get_current_local_time"
      :description "Returns current local system time in ISO 8601 format."
      :inputSchema {:type "object" :properties {} :required []}
      :handler (fn [_args]
                 (.format (ZonedDateTime/now (ZoneId/systemDefault))
                          (DateTimeFormatter/ISO_ZONED_DATE_TIME)))}
   ]})

(defn -main [& _args]
  (run-server time-server))
```

### 2. Testing the Server via CLI

Because the server communicates purely over standard input/output, you can test it instantly from your terminal by piping JSON-RPC requests into it:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-24","capabilities":{},"clientInfo":{"name":"test"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_current_local_time","arguments":{}}}' | clj -M:time-server
```

### 3. Using with Claude Desktop / MCP Clients

Once you compile your Clojure MCP server into an uberjar (or run it via `clj`), you can register it in any MCP client's configuration file:

```json
{
  "mcpServers": {
    "clojure-time-server": {
      "command": "java",
      "args": ["-jar", "/path/to/your/time-server.jar"]
    }
  }
}
```

---

## Architecture

- `mcp.protocol`: Pure data definitions for JSON-RPC 2.0 and MCP message payloads.
- `mcp.transport`: Abstract protocol for transport layer communication.
- `mcp.stdio`: Subprocess management via `java.lang.ProcessBuilder`. Background thread reads stdout and delivers responses to Clojure `promise`s.
- `mcp.http`: Implements the `2026-07-24` StreamableHTTP spec. Uses `clj-http` to POST requests and automatically captures the `Mcp-Session-Id`.
- `mcp.client`: High-level API managing request IDs and capability negotiation.
- `mcp.server`: Reads from `System/in`, routes JSON-RPC methods to your Clojure handlers, and writes to `System/out`.

---

Here is the updated License section to replace the bottom of your `README.md`.

---

## License

Copyright © 2024 Pooria Yousefi

Licensed under the Eclipse Public License - v 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at:

```text
    https://www.eclipse.org/legal/epl-2.0/
```

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

This Source Code may also be made available under the following Secondary Licenses when the conditions for such availability set forth in the Eclipse Public License, v. 2.0 are satisfied: GNU General Public License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any later version, with the GNU Classpath Exception which is available at https://www.gnu.org/software/classpath/license.html.
