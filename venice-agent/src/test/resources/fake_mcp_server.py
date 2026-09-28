import sys, json

def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()

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
            # First page: one tool, plus a cursor pointing at a second page.
            send({
                "jsonrpc": "2.0",
                "id": msg["id"],
                "result": {
                    "tools": [
                        {"name": "echo", "description": "Echoes the input text back.", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}},
                    ],
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
                    ],
                },
            })
    elif method == "tools/call":
        name = msg["params"]["name"]
        args = msg["params"]["arguments"]
        if name == "fail":
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"isError": True, "content": [{"type": "text", "text": "boom"}]}})
        else:
            text = args.get("text", "")
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [{"type": "text", "text": f"echo: {text}"}]}})
