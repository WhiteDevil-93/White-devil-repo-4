# venice-agent

A small Kotlin/JVM CLI agent that talks to the [Venice AI](https://venice.ai) API
(OpenAI-compatible `chat/completions`) and can call tools — read/write files, list
directories, and optionally run shell commands — in an agentic tool-use loop.

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
- `ToolBox` — defines the tool schemas sent to the model and executes tool calls,
  sandboxing all file paths to the configured workspace directory.
- `Agent` — the tool-use loop: sends the conversation, and whenever the model
  responds with `tool_calls`, executes them locally, feeds the results back as
  `role: "tool"` messages, and repeats until the model returns a final answer
  (capped at `maxToolIterations` round-trips).

## Security notes

- `run_shell_command` is **disabled by default**. Only enable `AGENT_ALLOW_SHELL=true`
  if you trust what you're asking the agent to do — it executes arbitrary shell
  commands inside the workspace directory with your user's permissions.
- File tools reject any path that resolves outside the workspace directory.
