(ns mcp.server
  "MCP 2026-07-28 server for the tools feature over stdio or Streamable HTTP."
  (:require [clojure.string :as str]
            [mcp.protocol :as protocol]
            [org.httpkit.server :as http])
  (:import (java.io BufferedReader InputStreamReader OutputStreamWriter PrintWriter)
           (java.util.concurrent Executors TimeUnit)))

(defn- public-tool [tool]
  (assoc (select-keys tool [:name :title :description :icons :outputSchema
                            :annotations :execution])
         :inputSchema (or (:inputSchema tool)
                          {:type "object"
                           :properties {}
                           :additionalProperties false})))

(defn- prepare-tools [tools]
  (let [tools (vec tools)
        names (map :name tools)]
    (when-not (every? #(and (string? %) (not (str/blank? %))) names)
      (throw (ex-info "Every tool must have a non-empty string :name" {})))
    (when-not (= (count names) (count (set names)))
      (throw (ex-info "Tool names must be unique" {:names names})))
    (doseq [{:keys [name handler inputSchema]} tools]
      (when-not (ifn? handler)
        (throw (ex-info "Every tool must have a callable :handler" {:name name})))
      (when (some? inputSchema)
        (when-not (map? inputSchema)
          (throw (ex-info ":inputSchema must be a map" {:name name})))
        (protocol/header-annotations inputSchema)))
    (into {} (map (juxt :name identity) tools))))

(defn create-server
  "Create a server from {:name :version :tools}. Tool handlers receive
  [arguments request-context] and return a text string or MCP tool result."
  [{:keys [name version instructions tools ttl-ms cache-scope]
    :or {version "1.0.0" tools [] ttl-ms 300000 cache-scope "private"}}]
  (when-not (and (string? name) (not (str/blank? name)))
    (throw (ex-info "create-server requires a non-empty :name" {})))
  (when-not (and (string? version) (not (str/blank? version)))
    (throw (ex-info "create-server requires a non-empty :version"
                    {:version version})))
  (when-not (and (integer? ttl-ms) (not (neg? ttl-ms)))
    (throw (ex-info ":ttl-ms must be a non-negative integer" {:ttl-ms ttl-ms})))
  (let [tool-index (prepare-tools tools)]
    {:info {:name name :version version}
     :instructions instructions
     :capabilities {:tools {:listChanged false}}
     :tools tool-index
     :ttl-ms ttl-ms
     :cache-scope cache-scope}))

(defn- validate-request [msg]
  (cond
    (not (map? msg))
    (protocol/error-response nil :invalid-request "Expected a JSON-RPC object")

    (not= "2.0" (:jsonrpc msg))
    (protocol/error-response (:id msg) :invalid-request
                             "jsonrpc must be \"2.0\"")

    (not (string? (:method msg)))
    (protocol/error-response (:id msg) :invalid-request
                             "method must be a string")

    (and (contains? msg :id)
         (not (or (string? (:id msg)) (number? (:id msg)))))
    (protocol/error-response nil :invalid-request
                             "id must be a string or number")

    (not (map? (or (:params msg) {})))
    (protocol/error-response (:id msg) :invalid-params "params must be an object")

    (not (contains? msg :id)) :notification

    (nil? (protocol/request-version msg))
    (protocol/error-response (:id msg) :invalid-request
                             "Missing protocol version in params._meta")

    (not (string? (protocol/request-version msg)))
    (protocol/error-response (:id msg) :invalid-request
                             "Protocol version in params._meta must be a string")

    (not= protocol/version (protocol/request-version msg))
    (protocol/error-response
     (:id msg) :unsupported-protocol-version
     (str "Unsupported protocol version: " (protocol/request-version msg))
     {:supported [protocol/version]
      :requested (protocol/request-version msg)})

    (not (and (map? (protocol/request-client-info msg))
              (string? (:name (protocol/request-client-info msg)))
              (string? (:version (protocol/request-client-info msg)))))
    (protocol/error-response (:id msg) :invalid-request
                             "clientInfo in params._meta requires string name and version")

    (not (map? (protocol/request-client-capabilities msg)))
    (protocol/error-response (:id msg) :invalid-request
                             "Missing clientCapabilities in params._meta")

    :else nil))

(defn- complete-result [srv id result]
  (protocol/response id result (:info srv)))

(defn- normalize-tool-result [result]
  (cond
    (nil? result)
    {:content []}

    (string? result)
    {:content [{:type "text" :text result}]}

    (not (map? result))
    {:content [{:type "text" :text (str result)}]}

    :else
    (let [result (if (contains? result :structuredContent)
                   (do
                     (when-not (map? (:structuredContent result))
                       (throw (ex-info ":structuredContent must be an object" {})))
                     (if (contains? result :content)
                       result
                       (assoc result :content
                              [{:type "text"
                                :text (protocol/write-json
                                       (:structuredContent result))}])))
                   result)]
      (when-not (or (nil? (:content result))
                    (sequential? (:content result)))
        (throw (ex-info "Tool result :content must be a sequence" {})))
      (update result :content #(or % [])))))

(defn- tool-result [srv msg tool]
  (let [params (:params msg)
        args (or (:arguments params) {})]
    (cond
      (not (map? args))
      (protocol/error-response (:id msg) :invalid-params
                               "tools/call arguments must be an object")

      :else
      (try
        (let [out (normalize-tool-result
                   ((:handler tool)
                    args
                    {:request msg
                     :cancelled? (fn []
                                   (boolean
                                    (some-> (:mcp/cancelled? msg) deref)))
                     :client-info (protocol/request-client-info msg)
                     :client-capabilities
                     (protocol/request-client-capabilities msg)}))]
          (complete-result srv (:id msg)
                           (assoc out :isError (boolean (:isError out)))))
        (catch Exception e
          (complete-result srv (:id msg)
                           {:isError true
                            :content [{:type "text"
                                       :text (str "Tool execution failed: "
                                                  (.getMessage e))}]}))))))

(defn dispatch
  "Dispatch one decoded JSON-RPC message. Notifications return nil."
  [srv msg]
  (let [invalid (validate-request msg)]
    (cond
      (= :notification invalid) nil
      invalid invalid
      :else
      (case (:method msg)
        "server/discover"
        (complete-result
         srv (:id msg)
         (cond-> {:supportedVersions [protocol/version]
                  :capabilities (:capabilities srv)
                  :ttlMs (:ttl-ms srv)
                  :cacheScope (:cache-scope srv)}
           (:instructions srv) (assoc :instructions (:instructions srv))))

        "tools/list"
        (if (:cursor (:params msg))
          (protocol/error-response (:id msg) :invalid-params
                                   "Pagination cursors are not supported")
          (complete-result
           srv (:id msg)
           {:tools (->> (:tools srv) vals (sort-by :name) (mapv public-tool))
            :ttlMs (:ttl-ms srv)
            :cacheScope (:cache-scope srv)}))

        "tools/call"
        (let [{:keys [name]} (:params msg)]
          (if-not (string? name)
            (protocol/error-response (:id msg) :invalid-params
                                     "tools/call requires params.name")
            (if-let [tool (get-in srv [:tools name])]
              (tool-result srv msg tool)
              (protocol/error-response (:id msg) :invalid-params
                                       (str "Unknown tool: " name)))))

        (protocol/error-response (:id msg) :method-not-found
                                 (str "Unknown method: " (:method msg)))))))

(defn- write-line! [lock ^PrintWriter writer s]
  (locking lock
    (.println writer s)
    (.flush writer)
    (when (.checkError writer)
      (throw (ex-info "Failed to write MCP response to stdout" {})))))

(defn- submit-message! [pool srv in-flight output-lock writer msg]
  (let [cancelled (atom false)
        id (when (map? msg) (:id msg))]
    (when (some? id) (swap! in-flight assoc id cancelled))
    (.submit pool
             ^Runnable
             (fn []
               (try
                 (let [resp (dispatch srv
                                      (assoc (if (map? msg) msg {})
                                             :mcp/cancelled? cancelled))]
                   (when (and resp (not @cancelled))
                     (write-line! output-lock writer
                                  (protocol/write-json resp))))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "MCP request failed:" (.getMessage e))))
                 (finally
                   (when (some? id)
                     (swap! in-flight dissoc id))))))))

(defn run-stdio
  "Serve newline-delimited UTF-8 JSON-RPC on stdin/stdout until stdin closes."
  [srv]
  (let [reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        writer (PrintWriter. (OutputStreamWriter. System/out "UTF-8"))
        output-lock (Object.)
        pool (Executors/newFixedThreadPool
              (max 2 (.availableProcessors (Runtime/getRuntime))))
        in-flight (atom {})]
    (try
      (loop []
        (let [line (.readLine reader)]
          (when line
            (try
              (let [msg (protocol/parse-json line)]
                (if (and (protocol/notification? msg)
                         (= "notifications/cancelled" (:method msg)))
                  (when-let [cancelled (get @in-flight
                                            (get-in msg [:params :requestId]))]
                    (reset! cancelled true))
                  (submit-message! pool srv in-flight output-lock writer msg)))
              (catch Exception _
                (write-line! output-lock writer
                             (protocol/write-json
                              (protocol/error-response nil :parse-error
                                                       "Invalid JSON")))))
            (recur))))
      (finally
        (.shutdown pool)
        (when-not (.awaitTermination pool 2 TimeUnit/SECONDS)
          (.shutdownNow pool))
        (.flush writer)))
      nil))

(defn- header-name [name]
  (str/lower-case name))

(defn- headers-map [headers]
  (into {} (map (fn [[k v]] [(header-name k) v])) headers))

(defn- loopback-origin? [origin]
  (try
    (let [uri (java.net.URI. origin)
          host (.getHost uri)]
      (and (#{"http" "https"} (.getScheme uri))
           (#{"localhost" "127.0.0.1" "::1"} (some-> host str/lower-case))))
    (catch Exception _ false)))

(defn- origin-valid? [headers allowed-origins]
  (if-let [origin (get headers "origin")]
    (or (contains? (set allowed-origins) origin)
        (loopback-origin? origin))
    true))

(defn- header-error [id message]
  (protocol/error-response id :header-mismatch message))

(defn- validate-param-headers [srv msg headers]
  (when (= "tools/call" (:method msg))
    (when-let [tool (get-in srv [:tools (get-in msg [:params :name])])]
      (some
       (fn [{:keys [name path type]}]
         (let [value (get-in (get-in msg [:params :arguments] {}) path)
               header (get headers (str/lower-case (str "Mcp-Param-" name)))
               mismatch (fn []
                          (header-error (:id msg)
                                        (str "Mcp-Param-" name
                                             " is missing or does not match the body")))]
           (if (nil? value)
             (when (some? header) (mismatch))
             (let [expected (protocol/header-value value)
                   actual (try (protocol/header-decode header)
                               (catch Exception _ ::invalid))]
               (when-not (and (protocol/header-type-matches? type value)
                              expected
                              (some? header)
                              (or (= expected actual)
                                  (and (= type "integer")
                                       (try
                                         (= (bigdec expected) (bigdec actual))
                                         (catch Exception _ false)))))
                 (mismatch))))))
       (protocol/header-annotations
        (:inputSchema (get-in srv [:tools (get-in msg [:params :name])] )))))))

(defn- validate-http-headers [srv msg headers]
  (let [headers (headers-map headers)
        method (:method msg)
        version (protocol/request-version msg)
        version-header (get headers "mcp-protocol-version")
        name-header (get headers "mcp-name")
        name-value (try (protocol/header-decode name-header)
                        (catch Exception _ ::invalid))]
    (or
     (when-not (= method (get headers "mcp-method"))
       (header-error (:id msg) "Missing or mismatched Mcp-Method"))
     (when-not (string? version-header)
       (header-error (:id msg) "Missing MCP-Protocol-Version"))
     (when-not (= version version-header)
       (header-error (:id msg) "MCP-Protocol-Version does not match request metadata"))
     (when (and (= "tools/call" method)
                (not= (str (get-in msg [:params :name])) name-value))
       (header-error (:id msg) "Mcp-Name does not match params.name"))
     (validate-param-headers srv msg headers))))

(defn- status-for [response]
  (case (get-in response [:error :code])
    -32700 400
    -32020 400
    -32022 400
    -32600 400
    -32601 404
    -32602 400
    200))

(defn- json-response [response]
  {:status (status-for response)
   :headers {"Content-Type" "application/json"}
   :body (protocol/write-json response)})

(defn- authenticated? [headers options]
  (if-let [authenticate (:authenticate options)]
    (boolean (authenticate headers))
    (if-let [expected (:bearer-token options)]
      (= (str "Bearer " expected) (get headers "authorization"))
      true)))

(defn- http-handler [srv options]
  (fn [request]
    (let [headers (headers-map (:headers request))]
      (cond
        (not= :post (:request-method request))
        {:status 405 :headers {"Allow" "POST"} :body ""}

        (not (authenticated? headers options))
        {:status 401 :headers {} :body ""}

        (not (origin-valid? headers (:allowed-origins options)))
        {:status 403 :headers {} :body ""}

        (not= "application/json"
              (some-> (get headers "content-type")
                      (str/split #";" 2)
                      first
                      str/trim
                      str/lower-case))
        {:status 415 :headers {"Content-Type" "application/json"} :body ""}

        :else
        (let [msg (try
                    (protocol/parse-json (slurp (:body request)))
                    (catch Exception _ nil))]
          (if-not msg
            (json-response (protocol/error-response nil :parse-error
                                                    "Invalid JSON"))
            (if-not (or (protocol/request? msg)
                        (protocol/notification? msg))
              (json-response (protocol/error-response
                              (:id msg) :invalid-request
                              "Invalid JSON-RPC message"))
              (let [hdr-error (validate-http-headers srv msg headers)]
                (cond
                  hdr-error (json-response hdr-error)
                  (protocol/notification? msg)
                  {:status 202 :headers {} :body ""}
                  :else (json-response (dispatch srv msg)))))))))))

(defn run-http
  "Start a Streamable HTTP MCP endpoint. Defaults to loopback; pass
  :ip/:port, optional :path, :bearer-token or :authenticate,
  and :allowed-origins."
  [srv {:keys [ip port path] :as options
        :or {ip "127.0.0.1" path "/mcp"}}]
  (when-not (and (integer? port) (<= 1 port 65535))
    (throw (ex-info "run-http requires a valid :port" {:port port})))
  (let [path (if (str/starts-with? path "/") path (str "/" path))
        app (http-handler srv options)
        stop (http/run-server
              (fn [request]
                (if (= path (:uri request))
                  (app request)
                  {:status 404 :headers {} :body ""}))
              {:ip ip :port port})]
    {:port port
     :stop (fn [] (stop :timeout 100))}))
