# venice-agent

A small Kotlin/JVM CLI agent that talks to the [Venice AI](https://venice.ai) API
(OpenAI-compatible `chat/completions`) and can call tools — read/write files, list
directories, optionally run shell commands, and call tools from connected
[MCP](https://modelcontextprotocol.io) servers — in an agentic tool-use loop.

## Requirements

- JDK 21 (the Gradle wrapper handles the rest)
- A Venice API key from https://venice.ai/settings/api

## Setup

```bash
export VENICE_API_KEY=your-key-here
```

Optional environment variables:

| Variable              | Default                          | Purpose                                             |
|------------------------|-----------------------------------|------------------------------------------------------|
| `VENICE_MODEL`          | `llama-3.3-70b`                  | Model id to use. See `GET /api/v1/models`.           |
| `VENICE_BASE_URL`       | `https://api.venice.ai/api/v1`   | API base URL.                                        |
| `AGENT_WORKSPACE_DIR`   | `./workspace`                    | Directory the file/shell tools are sandboxed to.     |
| `AGENT_ALLOW_SHELL`     | `false`                          | Set to `true` to enable the `run_shell_command` tool.|
| `AGENT_WEB_SEARCH`      | `false`                          | Set to `true` to enable Venice's built-in web search.|
| `AGENT_MCP_CONFIG`      | `./mcp-servers.json`             | Path to an MCP server config file (optional; skipped if absent). |

## Connectors and plugins (MCP)

Tools aren't limited to the built-in file/shell set. Any local [MCP](https://modelcontextprotocol.io)
server (the same config format Claude Desktop uses) can be plugged in over stdio: copy
`mcp-servers.json.example` to `mcp-servers.json` and list the servers to launch:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/allow"]
    }
  }
}
```

Each configured server is spawned as a subprocess and spoken to over newline-delimited
JSON-RPC (the MCP stdio transport). Its tools are exposed to the model namespaced as
`<serverName>__<toolName>` (e.g. `filesystem__read_file`) to avoid clashing with the
built-in tools or other servers.

### Writing your own plugin

Tools are pluggable via the `ToolProvider` interface (`definitions()` + `execute()`);
`ToolBox` (built-ins) and `McpStdioClient` (MCP servers) are just two implementations
registered together in a `ToolRegistry`. To add a tool source that isn't an MCP server,
implement `ToolProvider` and add it to the `ToolRegistry(...)` list in `Main.kt`.

## Run

Interactive REPL:

```bash
./gradlew run
```

One-shot task:

```bash
./gradlew run --args="Summarize the files in the workspace"
```

Type `exit` or `quit` to leave the REPL.

## How it works

- `VeniceClient` — thin Ktor HTTP client for `POST /chat/completions`.
- `ToolProvider` — the plugin interface every tool source implements (`definitions()` +
  `execute()`).
- `ToolBox` — the built-in `ToolProvider`: file/shell tools sandboxed to the configured
  workspace directory.
- `mcp/McpStdioClient` — a `ToolProvider` that launches an MCP server subprocess and
  speaks JSON-RPC to it over stdio, exposing its tools namespaced as `server__tool`.
- `ToolRegistry` — merges all configured `ToolProvider`s into one namespace for the agent.
- `Agent` — the tool-use loop: sends the conversation, and whenever the model
  responds with `tool_calls`, executes them via the `ToolRegistry`, feeds the results
  back as `role: "tool"` messages, and repeats until the model returns a final answer
  (capped at `maxToolIterations` round-trips).

## Security notes

- `run_shell_command` is **disabled by default**. Only enable `AGENT_ALLOW_SHELL=true`
  if you trust what you're asking the agent to do — it executes arbitrary shell
  commands inside the workspace directory with your user's permissions.
- File tools reject any path that resolves outside the workspace directory.
