import sys, json

def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()

state = {"list_version": 1}

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    msg = json.loads(line)
    method = msg.get("method")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"protocolVersion": "2024-11-05", "capabilities": {}, "serverInfo": {"name": "fake", "version": "0.1"}}})
    elif method == "notifications/initialized":
        pass
    elif method == "tools/list":
        params = msg.get("params") or {}
        cursor = params.get("cursor")
        if cursor is None:
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
        elif name == "fail":
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"isError": True, "content": [{"type": "text", "text": "boom"}]}})
        elif name in ("foo.bar", "foo_bar"):
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": f"called: {name}"}]}})
        else:
            text = args.get("text", "")
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": f"echo: {text}"}]}})
