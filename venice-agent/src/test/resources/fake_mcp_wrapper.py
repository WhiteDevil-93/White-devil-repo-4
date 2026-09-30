"""
Simulates a launcher command (a wrapper script, `npx`, ...) that spawns its own detached child
process which doesn't automatically die when the launcher does. The wrapper itself speaks just
enough MCP to let a client connect and list a tool; the child continuously rewrites a marker file
so a test can tell, from outside, whether it's still alive.
"""
import sys, json, subprocess, os

marker_path = sys.argv[1]
child_script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fake_child_marker.py")
# sys.executable, not "python3": on Windows that name is the Microsoft Store
# stub, so the detached child never started and the test timed out waiting
# for its marker file. Reusing the running interpreter is right everywhere.
subprocess.Popen([sys.executable, child_script, marker_path])


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
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"protocolVersion": "2024-11-05", "capabilities": {}, "serverInfo": {"name": "wrapper", "version": "0.1"}}})
    elif method == "notifications/initialized":
        pass
    elif method == "tools/list":
        send({"jsonrpc": "2.0", "id": msg["id"], "result": {"tools": [{"name": "echo", "description": "", "inputSchema": {"type": "object"}}]}})
