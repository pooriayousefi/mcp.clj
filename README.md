# mcp.clj

A small Clojure MCP client and server for **tool calling in agent runtimes**.
It targets MCP **2026-07-28** and provides local stdio and remote Streamable
HTTP transports, tool discovery/execution, and an adapter for OpenAI-compatible
function calling.

The project intentionally does not negotiate older MCP revisions. It
implements only the tool-calling surface: it does not implement resources,
prompts, sampling, elicitation, tasks, subscriptions, OAuth discovery, or the
older initialization/session transports.

## What you will build

This tutorial walks through:

1. Running the project and its tests.
2. Creating and running a tool server.
3. Connecting to one or more MCP servers.
4. Giving their tools to an OpenAI-compatible model and executing its tool
   calls.

## Requirements and running the project

- Java 17 or newer.
- Clojure CLI.

Clone the repository and run the test suite:

```sh
git clone https://github.com/pooriayousefi/mcp.clj.git
cd mcp.clj
clojure -M:test
```

The `:test` alias adds `test/` to the classpath. Source namespaces are under
`src/`. To check that the library namespaces compile:

```sh
clojure -M -e "(require 'mcp.protocol 'mcp.client 'mcp.server 'mcp.openai)"
```

The project does not define an uberjar or native-image build. Run a server
with the Clojure CLI, or package it using the build tooling of your application.

### Use this checkout as a dependency

While developing another local Clojure application, add a local-root dependency
to that application's `deps.edn` (adjust the path to this checkout):

```clojure
{:deps {mcp.clj/mcp.clj {:local/root "../mcp.clj"}}}
```

Your application can then require `mcp.client`, `mcp.server`, and
`mcp.openai`. A published artifact can be used instead once a version is
released.

## 1. Build a tool server

A server is a map created with `mcp.server/create-server`. Each tool has a
unique name, an optional description, an object-shaped JSON Schema, and a
handler. The handler is called with:

1. The decoded argument map.
2. A request context containing `:request`, `:client-info`,
   `:client-capabilities`, and `:cancelled?`.

The handler may return a string or a result map. Use `:content` for ordinary
tool output; return `:structuredContent` when downstream code benefits from
structured data. Tool errors should normally be returned with `:isError true`.
Unhandled exceptions are also converted into tool-level error results.

```clojure
(ns demo.math
  (:require [mcp.server :as mcp]))

(def server
  (mcp/create-server
   {:name "math"
    :version "1.0.0"
    :tools
    [{:name "add"
      :description "Add two numbers."
      :inputSchema
      {:type "object"
       :properties {:a {:type "number"}
                    :b {:type "number"}}
       :required ["a" "b"]
       :additionalProperties false}
      :handler
      (fn [{:keys [a b]} _request-context]
        {:structuredContent {:sum (+ a b)}})}]}))
```

The schema is advertised to clients and models. This implementation checks
that tool arguments are objects, but does not validate every argument against
the full JSON Schema; validate important input in your handler.

### Run the server over stdio

Stdio is the usual choice when an agent runtime launches its MCP server as a
child process:

```clojure
(mcp/run-stdio server)
```

Create a `-main` in your application, for example in `src/demo/math.clj`:

```clojure
(defn -main [& _args]
  (mcp/run-stdio server))
```

Then launch it from a terminal or process manager:

```sh
clojure -M -m demo.math
```

The child process must write protocol messages only to stdout. Send diagnostic
logs to stderr instead.

### Run the server over Streamable HTTP

HTTP is useful when your runtime connects to a separately hosted server:

```clojure
(def http-server (mcp/run-http server {:port 3000}))

;; During orderly application shutdown:
((:stop http-server))
```

The endpoint defaults to `/mcp` and binds to `127.0.0.1`. To change the bind
address, endpoint path, or authentication:

```clojure
(def http-server
  (mcp/run-http server
                {:ip "127.0.0.1"
                 :port 3000
                 :path "/mcp"
                 :bearer-token (System/getenv "MCP_BEARER_TOKEN")
                 :allowed-origins #{"https://agent.example.com"}}))
```

For custom authentication, supply `:authenticate`, a function that receives
the lower-case HTTP headers map and returns truthy only for authorized
requests. Do not bind to a public interface without deliberately configuring
authentication, origin checks, and network protections. The HTTP server emits
single JSON responses; the client also accepts request-scoped SSE responses
from other conforming servers.

## 2. Connect to MCP servers

The client supports a process command over stdio or a full HTTP endpoint URL.
`mcp/connect` is a convenience function for either configuration:

```clojure
(require '[mcp.client :as mcp])

(def local
  (mcp/connect {:command ["clojure" "-M" "-m" "demo.math"]
                :timeout-ms 30000}))

(def remote
  (mcp/connect {:url "http://127.0.0.1:3000/mcp"
                :headers {"Authorization"
                          (str "Bearer " (System/getenv "MCP_BEARER_TOKEN"))}
                :timeout-ms 30000}))
```

The same connections can be created explicitly with `connect-stdio` and
`connect-http`. For stdio, optional `:cwd` and `:env` configure the child
process. For either transport, `:timeout-ms`, `:client-info`, and
`:capabilities` customize requests.

Discover server information, list tools, and call a tool:

```clojure
(mcp/discover local)
(mcp/list-tools local)
(mcp/call-tool local "add" {:a 20 :b 22})
;; => {:resultType "complete"
;;     :structuredContent {:sum 42}
;;     :content [{:type "text" :text "{\"sum\":42}"}]
;;     ...}
```

`list-tools` follows pagination cursors and caches the result according to
the server's `ttlMs`. `call-tool` returns the MCP result map. JSON-RPC errors,
transport failures, and timeouts are thrown as `ExceptionInfo`; inspect
`(ex-data e)` for details.

### Connect to several servers

Keep each connection under a distinct key. Those keys are used to namespace
the functions exposed to the model:

```clojure
(def connections
  {"math" (mcp/connect-stdio ["clojure" "-M" "-m" "demo.math"])
   "time" (mcp/connect-stdio ["clojure" "-M:time-server"])
   "remote-search" (mcp/connect-http "https://tools.example.com/mcp")})

(try
  (doseq [[server-name connection] connections]
    (println server-name (mapv :name (mcp/list-tools connection))))
  ;; Use the connections below.
  (finally
    (doseq [connection (vals connections)]
      (mcp/close! connection))))
```

Close stdio connections when finished; this closes their child processes.
Closing an HTTP client connection releases local client state.

## 3. Use tools with an OpenAI-compatible model

`mcp.openai/tools` lists the tools from each connection and converts them to
OpenAI-compatible function-tool definitions. It returns both the definitions
to send to the model and a routing table used to execute model-produced calls:

```clojure
(require '[mcp.openai :as mcp-openai])

(def tool-set (mcp-openai/tools connections))
(def model-tools (:tools tool-set))
(def tool-routes (:routes tool-set))
```

Pass `model-tools` to your provider's chat-completions API along with the
conversation. Provider SDKs differ, so the following `chat-completions!` is a
placeholder for your OpenAI-compatible provider client:

```clojure
(defn run-agent-turn
  [messages]
  (loop [messages messages]
    (let [response
          (chat-completions!
           {:messages messages
            :tools model-tools
            :tool_choice "auto"})
          choice (first (:choices response))
          assistant-message (:message choice)]
      (if (= "tool_calls" (:finish_reason choice))
        (let [tool-messages
              (mcp-openai/execute-tool-calls tool-routes assistant-message)]
          ;; Keep the assistant message and pair every tool result with its
          ;; original tool_call_id before asking the model for its next turn.
          (recur (into (conj messages assistant-message) tool-messages)))
        {:messages (conj messages assistant-message)
         :response response}))))
```

Each result from `execute-tool-calls` has the OpenAI tool-message shape,
including the original `:tool_call_id`. Calls in one assistant message are
executed concurrently; returned results preserve the original call order.
The host remains responsible for authorization, tool allow-lists, user
approval, model limits, and deciding when to stop the agent loop.

### Restrict tools per agent

Give each agent only the servers it is allowed to use. Build a separate tool
set and route table from a subset of connections:

```clojure
(def research-tools
  (mcp-openai/tools (select-keys connections ["remote-search"])))

(def finance-tools
  (mcp-openai/tools (select-keys connections ["math"])))
```

Do not pass a shared route table to an agent that should not be able to invoke
those tools.

## HTTP header annotations

For HTTP calls, parameters in a tool schema can use `:x-mcp-header` to mirror
selected values into request headers. Annotated values must be strings,
integers, or booleans. The client encodes unsafe values, and the server checks
the header against the request body:

```clojure
:inputSchema
{:type "object"
 :properties {:tenant {:type "string" :x-mcp-header "Tenant"}}
 :required ["tenant"]}
```

This feature is for HTTP-aware routing or intermediaries; it is not required
for ordinary tool calls. Keep authorization decisions in authenticated server
code rather than trusting a mirrored header by itself.

## Protocol scope

- Fixed protocol version: `2026-07-28`; each request includes version,
  client identity, and client capabilities.
- `server/discover`, `tools/list`, and `tools/call`.
- Newline-delimited UTF-8 JSON-RPC over stdio.
- Stateless Streamable HTTP POST requests with required MCP headers,
  origin validation, optional bearer/custom authentication, and support for
  JSON or SSE responses in the client.
- OpenAI-compatible tool definition conversion and tool-call execution.

Older MCP versions are rejected; there is no initialize handshake. Features
outside tool calling are intentionally not implemented.

## Tests

Run the unit and integration suite:

```sh
clojure -M:test
```

The suite exercises protocol metadata/version checks, tool dispatch, stdio and
HTTP client/server round trips, HTTP header/origin validation, and OpenAI
tool-call routing.
