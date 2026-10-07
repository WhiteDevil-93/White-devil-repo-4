"""A tiny MCP server shaped like the desktop-control connector (Kimi CU): a screenshot tool that returns an image,
a click tool, and a tool whose schema has a top-level oneOf. Used by ComputerUseTest."""
import base64
import io
import json
import struct
import sys
import zlib


def png(w, h):
    raw = b"".join(b"\x00" + b"\x10\x20\x30" * w for _ in range(h))
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


def send(obj):
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


TOOLS = [
    {"name": "screenshot", "description": "Screenshot of a window.", "inputSchema": {"type": "object", "properties": {}}},
    {"name": "click", "description": "Click.", "inputSchema": {"type": "object", "properties": {"x": {"type": "integer"}}}},
    {"name": "get_app_state", "description": "State of one window.", "inputSchema": {
        "additionalProperties": False, "oneOf": [{"required": ["pid"]}, {"required": ["app"]}],
        "properties": {"pid": {"type": "integer"}, "app": {"type": "string"}}, "type": "object"}},
]

for line in sys.stdin:
    try:
        msg = json.loads(line)
    except ValueError:
        continue
    method = msg.get("method")
    if method == "initialize":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                                                           "serverInfo": {"name": "fake-cu", "version": "0"}}})
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": TOOLS}})
    elif method == "tools/call":
        name = msg["params"]["name"]
        if name == "screenshot":
            data = base64.b64encode(png(2600, 1300)).decode()
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [
                {"type": "text", "text": "window: Notepad"}, {"type": "image", "mimeType": "image/png", "data": data}]}})
        else:
            send({"jsonrpc": "2.0", "id": msg["id"], "result": {"content": [
                {"type": "text", "text": f"did {name} {json.dumps(msg['params'].get('arguments', {}))}"}]}})
    elif "id" in msg:
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {}})
