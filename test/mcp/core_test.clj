(ns mcp.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp.client :as client]
            [mcp.openai :as openai]
            [mcp.protocol :as protocol]
            [mcp.server :as server]
            [org.httpkit.client :as http])
  (:import (java.net ServerSocket)))

(defn- echo-server []
  (server/create-server
   {:name "echo"
    :version "1.0"
    :tools [{:name "echo"
             :description "Echo a message."
             :inputSchema {:type "object"
                           :properties {:message {:type "string"
                                                  :x-mcp-header "Message"}}
                           :required ["message"]}
             :handler (fn [{:keys [message]} _]
                        {:content [{:type "text" :text message}]})}]}))

(defn- request [id method params]
  (protocol/request id method params))

(deftest protocol-wire-format
  (let [message (request "id" "tools/list" {})
        wire (protocol/write-json message)]
    (is (= protocol/version (protocol/request-version message)))
    (is (re-find #"protocolVersion" wire))
    (is (= protocol/version
           (get-in (protocol/parse-json wire)
                   [:params :_meta
                    :io.modelcontextprotocol/protocolVersion])))))

(deftest tool-dispatch
  (let [srv (echo-server)
        listed (server/dispatch srv (request "1" "tools/list" {}))
        called (server/dispatch srv (request "2" "tools/call"
                                             {:name "echo"
                                              :arguments {:message "hello"}}))
        missing (server/dispatch srv (request "3" "tools/call"
                                              {:name "absent"
                                               :arguments {}}))]
    (is (= ["echo"] (mapv :name (get-in listed [:result :tools]))))
    (is (= "complete" (get-in called [:result :resultType])))
    (is (= [{:type "text" :text "hello"}]
           (get-in called [:result :content])))
    (is (= -32602 (get-in missing [:error :code])))))

(deftest protocol-version-is-strict
  (let [message (assoc-in (request "1" "tools/list" {})
                          [:params :_meta
                           :io.modelcontextprotocol/protocolVersion]
                          "2025-11-25")
        response (server/dispatch (echo-server) message)]
    (is (= -32022 (get-in response [:error :code])))
    (is (= ["2026-07-28"] (get-in response [:error :data :supported])))))

(deftest unsafe-header-value-round-trips
  (let [value "hello, 世界 "
        encoded (protocol/header-encode value)]
    (is (re-matches #"=\?base64\?.+\?=" encoded))
    (is (= value (protocol/header-decode encoded)))
    (is (protocol/integer-number? 42.0))
    (is (= "42" (protocol/header-value 42.0)))))

(deftest http-client-server-openai-integration
  (with-open [socket (ServerSocket. 0)]
    (let [port (.getLocalPort socket)]
      (.close socket)
      (let [{:keys [stop]} (server/run-http (echo-server) {:port port})
            conn (client/connect-http (str "http://127.0.0.1:" port "/mcp"))]
        (try
          (let [raw @(http/post (str "http://127.0.0.1:" port "/mcp")
                                {:headers {"Content-Type" "application/json"
                                           "Accept" "application/json"}
                                 :body (protocol/write-json
                                        (request "bad-header" "tools/list" {}))})]
            (is (= 400 (:status raw)))
            (is (= -32020 (:code (:error (protocol/parse-json (:body raw)))))))
          (let [raw @(http/post (str "http://127.0.0.1:" port "/mcp")
                                {:headers {"Origin" "https://attacker.example"
                                           "Content-Type" "application/json"}
                                 :body (protocol/write-json
                                        (request "bad-origin" "tools/list" {}))})]
            (is (= 403 (:status raw))))
          (let [message (assoc-in (request "old-version" "tools/list" {})
                                  [:params :_meta
                                   "io.modelcontextprotocol/protocolVersion"]
                                  "2025-11-25")
                raw @(http/post (str "http://127.0.0.1:" port "/mcp")
                                {:headers {"Content-Type" "application/json"
                                           "MCP-Protocol-Version" "2025-11-25"
                                           "Mcp-Method" "tools/list"}
                                 :body (protocol/write-json message)})
                error (:error (protocol/parse-json (:body raw)))]
            (is (= 400 (:status raw)))
            (is (= -32022 (:code error))))
          (is (= ["2026-07-28"] (:supportedVersions (client/discover conn))))
          (is (= ["echo"] (mapv :name (client/list-tools conn))))
          (let [unsafe " padded 世界 "]
            (is (= [{:type "text" :text unsafe}]
                   (:content (client/call-tool conn "echo" {:message unsafe})))))
          (let [{:keys [tools routes]} (openai/tools {"worker" conn})
                function-name (get-in (first tools) [:function :name])
                messages (openai/execute-tool-calls
                          routes
                          {:tool_calls
                           [{:id "call_1"
                             :type "function"
                             :function {:name function-name
                                        :arguments "{\"message\":\"agent\"}"}}
                            {:id "call_2"
                             :type "function"
                             :function {:name function-name
                                        :arguments {"message" "again"}}}]})]
            (is (= "call_1" (:tool_call_id (first messages))))
            (is (= "agent" (:content (first messages))))
            (is (= "call_2" (:tool_call_id (second messages))))
            (is (= "again" (:content (second messages))))
            (is (= "function" (get-in (first tools) [:type]))))
          (finally
            (client/close! conn)
            (stop)))))))

(deftest stdio-client-server-round-trip
  (let [conn (client/connect-stdio ["clojure" "-M:time-server"]
                                   {:cwd (System/getProperty "user.dir")})]
    (try
      (is (= ["get_current_local_time" "get_current_utc_time"]
             (mapv :name (client/list-tools conn))))
      (is (string? (get-in (client/call-tool conn "get_current_utc_time" {})
                           [:structuredContent :iso])))
      (finally
        (client/close! conn)))))

(deftest execution-errors-are-tool-results
  (let [srv (server/create-server
             {:name "failure"
              :tools [{:name "fail"
                       :handler (fn [_ _] (throw (ex-info "nope" {})))}]})
        response (server/dispatch srv (request "1" "tools/call"
                                               {:name "fail"
                                                :arguments {}}))]
    (is (true? (get-in response [:result :isError])))
    (is (re-find #"nope" (get-in response [:result :content 0 :text])))))
