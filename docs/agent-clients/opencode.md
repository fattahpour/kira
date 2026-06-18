# Configure OpenCode For Kira

This guide shows how to configure OpenCode to work on this repo and use Kira MCP.

## 1. Project Instructions

OpenCode can create or update `AGENTS.md` with:

```text
/init
```

For this repo, create:

```text
AGENTS.md
```

Recommended content:

```markdown
# Kira Agent Instructions

Kira is a Java 21 / Spring Boot local retrieval service.

## Workflow

- Use Kira MCP before broad file reads.
- Prefer `keyword_search`, `discover_symbols`, and `get_symbol`.
- Use `answer_context` for compact context.
- Use `docs/MCP_KIRA_TOOL_REFERENCES.md` for all MCP services and call examples.
- Run `mvn test -q` after code changes when practical.
- Update docs when behavior or user workflow changes.
```

You can reuse [shared-instructions-template.md](shared-instructions-template.md).

For the complete MCP service list, use [../MCP_KIRA_TOOL_REFERENCES.md](../MCP_KIRA_TOOL_REFERENCES.md).

## 2. Configure Extra Instruction Files

OpenCode can load extra instruction files from `opencode.json`.

Project config:

```text
opencode.json
```

Example:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "instructions": [
    "AGENTS.md",
    "docs/agent-clients/shared-instructions-template.md"
  ]
}
```

Global config:

```text
~/.config/opencode/opencode.json
```

Use global config for personal preferences and project config for team rules.

## 3. Configure Kira MCP

OpenCode config uses the `mcp` field.

HTTP/SSE example:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "kira": {
      "type": "remote",
      "url": "http://localhost:8094/sse",
      "enabled": true
    }
  }
}
```

If your OpenCode version expects a different transport key, use `opencode mcp add` for guided setup:

```bash
opencode mcp add
opencode mcp list
```

Stdio example:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "kira": {
      "type": "local",
      "command": ["java", "-jar", "<kira-install-dir>/target/ai-retrieval-0.1.0-SNAPSHOT.jar", "--spring.ai.mcp.server.stdio=true", "--spring.ai.mcp.server.type=SYNC", "--kira.reranker.enabled=false", "--server.port=0"],
      "enabled": true
    }
  }
}
```

Build the JAR first:

```bash
mvn clean package
```

## 4. Ignore Noisy Watch Paths

OpenCode can ignore noisy paths in its file watcher:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "watcher": {
    "ignore": [
      "target/**",
      ".git/**",
      ".idea/**",
      ".vscode/**",
      "node_modules/**"
    ]
  }
}
```

This does not replace Kira file exclusion. Use `docs/FILE_EXCLUSION_GUIDE.md` for Kira indexing filters.

## 5. Verify In OpenCode

From the repo root:

```bash
cd <kira-install-dir>
opencode
```

Then:

```text
Use Kira MCP. Call index_status and then keyword_search for RetrievalOrchestrator.
```

## References

- https://opencode.ai/docs/
- https://opencode.ai/docs/config/
- https://opencode.ai/docs/rules/
- https://opencode.ai/docs/mcp-servers/
- https://opencode.ai/docs/cli/
