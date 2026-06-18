# Configure Claude Code For Kira

This guide shows how to configure Claude Code to work on this repo and use Kira MCP.

## 1. Project Instructions

Claude Code reads project instructions from `CLAUDE.md`. This repo already has:

```text
CLAUDE.md
```

Use it for durable project rules such as:

- Java 21 / Spring Boot constraints.
- Kira architecture rules.
- Build and test commands.
- Kira-first retrieval workflow.
- Docs that should be updated when behavior changes.

Personal Claude Code configuration belongs under:

```text
~/.claude
```

Project-shared files should stay in the repo.

## 2. MCP Option A: Use The Existing `.mcp.json`

This repo already has:

```text
.mcp.json
```

It contains both:

- `kira-stdio`: starts Kira as a child Java process.
- `kira-http`: connects to a running Kira HTTP/SSE service at `http://localhost:8080/sse`.

Build the JAR first:

```bash
mvn clean package
```

Then start Claude Code from the repo root:

```bash
cd <kira-install-dir>
claude
```

Inside Claude Code, use:

```text
/mcp
```

Confirm Kira is listed.

## 3. MCP Option B: Claude Settings File

Some setups use Claude settings files instead of `.mcp.json`.

Project scope:

```text
.claude/settings.json
```

Example HTTP/SSE server:

```json
{
  "mcpServers": {
    "kira-http": {
      "transportType": "http",
      "url": "http://localhost:8080/sse"
    }
  }
}
```

Example stdio server:

```json
{
  "mcpServers": {
    "kira-stdio": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-jar",
        "<kira-install-dir>/target/ai-retrieval-0.1.0-SNAPSHOT.jar",
        "--spring.ai.mcp.server.stdio=true",
        "--spring.ai.mcp.server.type=SYNC",
        "--kira.reranker.enabled=false",
        "--server.port=0"
      ]
    }
  }
}
```

Do not commit local secrets. Put sensitive values in environment variables or local-only settings.

## 4. Verify Kira Tools

Ask Claude:

```text
Use Kira MCP. Call index_status, then search_code for RetrievalOrchestrator in repo kira.
```

Expected behavior:

1. Claude checks `index_status()`.
2. Claude uses search before broad file reads.
3. Claude drills into symbols with `get_symbol` only when needed.

## 5. Suggested Claude Instruction Addition

Add this to `CLAUDE.md` if it is missing:

```markdown
## Kira-First Search

Before reading many files, use Kira MCP:

- `keyword_search` for exact identifiers.
- `discover_symbols` or `find_symbol` for symbol discovery.
- `get_symbol` for one known Java symbol.
- `answer_context` for compact final context.
- `docs/MCP_KIRA_TOOL_REFERENCES.md` for every MCP service and call example.

Read full files only after Kira identifies the relevant path or FQN.
```

For the complete MCP service catalog, use:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

## References

- https://code.claude.com/docs/en/memory
- https://code.claude.com/docs/en/claude-directory
