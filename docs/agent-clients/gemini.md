# Configure Gemini CLI For Kira

This guide shows how to configure Gemini CLI to work on this repo and use Kira MCP.

## 1. Project Instructions

Gemini CLI uses `GEMINI.md` context files. This repo already has:

```text
GEMINI.md
```

If it is empty or too small, add:

```markdown
# Kira Project Context

Kira is a Java 21 / Spring Boot local retrieval service.

## Workflow

- Use Kira MCP before broad file reads.
- Use `keyword_search` for exact terms.
- Use `discover_symbols` or `find_symbol` before `get_symbol`.
- Use `answer_context` for compact final context.
- Use `docs/MCP_KIRA_TOOL_REFERENCES.md` for all MCP services and call examples.
- Run `mvn test -q` after code changes when practical.
```

Global Gemini instructions can live at:

```text
~/.gemini/GEMINI.md
```

Gemini can also import smaller Markdown files from `GEMINI.md` using `@file.md` syntax. For example:

```markdown
@./docs/agent-clients/shared-instructions-template.md
```

## 2. Configure MCP In Settings

Gemini CLI uses `settings.json` for persistent configuration.

Project-local settings:

```text
.gemini/settings.json
```

User settings:

```text
~/.gemini/settings.json
```

Example using Kira HTTP/SSE:

```json
{
  "mcpServers": {
    "kira": {
      "url": "http://localhost:8094/sse"
    }
  }
}
```

Start Kira separately for HTTP/SSE:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --spring.ai.mcp.server.stdio=false \
  --spring.ai.mcp.server.type=ASYNC \
  --server.port=8094
```

## 3. Optional: Use Multiple Instruction File Names

If you want Gemini to also read `AGENTS.md`, configure context filenames:

```json
{
  "context": {
    "fileName": ["AGENTS.md", "GEMINI.md"]
  }
}
```

This is useful when Codex, OpenCode, and Gemini should share the same project instructions.

## 4. Verify In Gemini

Start Gemini from the repo root:

```bash
cd <kira-install-dir>
gemini
```

Check loaded memory:

```text
/memory show
```

After editing `GEMINI.md`, reload memory:

```text
/memory reload
```

Then ask:

```text
Use Kira MCP. Call index_status, then search_knowledge for file exclusion guide in repo kira.
```

## 5. Suggested Gemini Prompt

```text
Use Kira MCP first. Prefer keyword_search, discover_symbols, get_symbol, and answer_context before reading files directly.
If Kira has no result, then inspect the repo with normal file search.
For full tool syntax, read docs/MCP_KIRA_TOOL_REFERENCES.md.
```

## References

- https://google-gemini.github.io/gemini-cli/docs/cli/gemini-md.html
- https://google-gemini.github.io/gemini-cli/docs/tools/mcp-server.html
- https://google-gemini.github.io/gemini-cli/docs/get-started/configuration.html
