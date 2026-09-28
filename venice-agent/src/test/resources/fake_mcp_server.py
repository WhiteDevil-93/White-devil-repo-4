import sys, json, time, os

def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()

hang_initialize = "--hang-initialize" in sys.argv
state = {"list_version": 1}

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    msg = json.loads(line)
    method = msg.get("method")
    if method == "initialize":
        if hang_initialize:
            continue  # never respond, simulating a server that's still starting up
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"protocolVersion": "2024-11-05", "capabilities": {}, "serverInfo": {"name": "fake", "version": "0.1"}}})
    elif method == "notifications/initialized":
        pass
    elif method == "notifications/cancelled":
        state["last_cancelled_request_id"] = (msg.get("params") or {}).get("requestId")
    elif method == "tools/list":
        params = msg.get("params") or {}
        cursor = params.get("cursor")
        if state.get("cursor_loop"):
            # Applies regardless of pagination position: the same cursor, forever.
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [], "nextCursor": "loop"}})
            continue
        if cursor is None:
            if state.get("fail_next_list"):
                state["fail_next_list"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "error": {"code": -32000, "message": "simulated transient tools/list failure"}})
                continue
            if state.get("malformed_next_list"):
                state["malformed_next_list"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "result": "not an object"})
                continue
            if state.get("missing_tools_field"):
                state["missing_tools_field"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "result": {}})  # no "tools" key at all
                continue
            if state.get("malformed_next_cursor"):
                state["malformed_next_cursor"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [], "nextCursor": 12345}})
                continue
            if state.get("malformed_tool_name"):
                state["malformed_tool_name"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [{"description": "no name field", "inputSchema": {"type": "object"}}]}})
                continue
            if state.get("malformed_tool_schema"):
                state["malformed_tool_schema"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [{"name": "bad_schema_tool", "description": "bad schema", "inputSchema": "not an object"}]}})
                continue
            if state.get("malformed_tool_entry_type"):
                state["malformed_tool_entry_type"] = False
                send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": ["not an object either"]}})
                continue
            # First page: one tool, plus a cursor pointing at a second page. After a
            # notifications/tools/list_changed has been sent, a new tool also appears here,
            # to prove a fresh tools/list is actually issued rather than serving a stale cache.
            page1_tools = [
                {"name": "echo", "description": "Echoes the input text back.", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
            ]
            if state["list_version"] >= 2:
                page1_tools.append({"name": "new_tool", "description": "Appeared after list_changed.", "inputSchema": {"type": "object", "properties": {}}})
            send({
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {
                    "tools": page1_tools,
                    "nextCursor": "page2",
                },
            })
        else:
            # Interleave an unsolicited server-to-client request before the real response, to
            # check the client doesn't mis-route it as the response to a pending call.
            send({"jsonrpc": "2.0", "id": 999999, "method": "ping", "params": {}})
            if state.get("emit_change_on_page2"):
                # Simulates a second, newer invalidation arriving while this very refresh is
                # still in flight (between page 1 and page 2).
                state["emit_change_on_page2"] = False
                send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {
                    "tools": [
                        {"name": "fail", "description": "Always reports a tool-level failure.", "inputSchema": {"type": "object", "properties": {}}},
                        # These two sanitize to the same "foo_bar" string, to exercise
                        # collision-safe naming.
                        {"name": "foo.bar", "description": "Collision candidate A.", "inputSchema": {"type": "object", "properties": {}}},
                        {"name": "foo_bar", "description": "Collision candidate B.", "inputSchema": {"type": "object", "properties": {}}},
                    ],
                },
            })
    elif method == "tools/call":
        name = msg["params"]["name"]
        args = msg["params"]["arguments"]
        if name == "trigger_list_changed":
            state["list_version"] = 2
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_list_changed_with_failure":
            state["list_version"] = 2
            state["fail_next_list"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_list_changed_mid_fetch":
            state["list_version"] = 2
            state["emit_change_on_page2"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "hang_forever":
            pass  # never respond; simulates a tool call the server never completes on its own
        elif name == "get_last_cancelled_request_id":
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": str(state.get("last_cancelled_request_id"))}]}})
        elif name == "trigger_malformed_list":
            state["malformed_next_list"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_missing_tools_field":
            state["missing_tools_field"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_malformed_next_cursor":
            state["malformed_next_cursor"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_malformed_tool_name":
            state["malformed_tool_name"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_malformed_tool_schema":
            state["malformed_tool_schema"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_malformed_tool_entry_type":
            state["malformed_tool_entry_type"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "trigger_cursor_loop":
            state["cursor_loop"] = True
            send({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "triggered"}]}})
        elif name == "exit_process":
            sys.exit(0)
        elif name == "stop_reading_stdin":
            # Acks first, then stops consuming stdin without exiting or closing the fd: the OS
            # pipe just fills up once the client writes enough, forcing a genuinely blocked write.
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "will stop reading"}]}})
            time.sleep(3600)
        elif name == "close_own_stdin_and_hang":
            # Acks first, then closes its own stdin (unlike stop_reading_stdin): a write on the
            # other end now fails immediately (broken pipe) instead of blocking on a full buffer.
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "closing stdin"}]}})
            sys.stdin.close()
            time.sleep(3600)
        elif name == "ping_then_exit":
            # Acks, sends an unsolicited ping, then exits immediately (os._exit, no cleanup):
            # by the time the client tries to reply to that ping, the pipe's read end is fully
            # gone (unlike merely closing the wrapped stdin object, which doesn't reliably do
            # this), so the write reliably fails with a broken pipe. This makes the client's
            # read-loop handler throw instead of the loop ending cleanly via EOF.
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": "pinging then exiting"}]}})
            send({"jsonrpc": "2.0", "id": 999998, "method": "ping", "params": {}})
            os._exit(1)
        elif name == "return_resource_content":
            if args.get("withText"):
                resource = {"uri": "file:///a.txt", "mimeType": "text/plain", "text": "hello resource"}
            else:
                resource = {"uri": "file:///a.bin", "mimeType": "application/octet-stream", "blob": "AAAA"}
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "resource", "resource": resource}]}})
        elif name == "fail":
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"isError": True, "content": [{"type": "text", "text": "boom"}]}})
        elif name in ("foo.bar", "foo_bar"):
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": f"called: {name}"}]}})
        else:
            text = args.get("text", "")
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": f"echo: {text}"}]}})
