# Configure Codex For Kira

This guide shows how to configure Codex CLI to work on this repo and use Kira MCP.

## 1. Add Project Instructions

Codex reads agent instructions from `AGENTS.md`. Use one global file for personal defaults and one repo file for project rules.

Global instructions:

```bash
mkdir -p ~/.codex
$EDITOR ~/.codex/AGENTS.md
```

Project instructions:

```bash
cd <kira-install-dir>
$EDITOR AGENTS.md
```

Recommended project content:

```markdown
# Kira Agent Instructions

Kira is a Java 21 / Spring Boot local retrieval service.

## Workflow

- Use Kira MCP search tools before broad file reads.
- Prefer `keyword_search`, `discover_symbols`, and `get_symbol` for exact code questions.
- Run `mvn test -q` after code changes when practical.
- Update docs when behavior or user workflow changes.
- Keep generated files, secrets, and runtime data out of commits.

## Important Docs

- `docs/INDEX.md`
- `docs/MCP_ENDPOINT_USAGE.md`
- `docs/MCP_KIRA_TOOL_REFERENCES.md`
- `docs/AGENT_INTEGRATION_GUIDE.md`
- `docs/FILE_EXCLUSION_GUIDE.md`
```

You can also copy from [shared-instructions-template.md](shared-instructions-template.md).

## 2. Configure Kira MCP

Codex uses TOML config. For user-level config, edit:

```text
~/.codex/config.toml
```

Add Kira as a stdio MCP server:

```toml
[mcp_servers.kira]
command = "java"
args = [
  "-jar",
  "<kira-install-dir>/target/ai-retrieval-0.1.0-SNAPSHOT.jar",
  "--spring.ai.mcp.server.stdio=true",
  "--spring.ai.mcp.server.type=SYNC",
  "--kira.reranker.enabled=false",
  "--server.port=0"
]
startup_timeout_sec = 60
```

If you use a trusted project-local Codex config, place the same block in:

```text
.codex/config.toml
```

Keep secrets out of committed config. Use environment variables for tokens.

## 3. Build Before First MCP Use

Codex starts the JAR, so build it first:

```bash
cd <kira-install-dir>
mvn clean package
```

## 4. Verify In Codex

Start Codex from the repo root:

```bash
cd <kira-install-dir>
codex
```

Then ask:

```text
Use Kira MCP. Run index_status and tell me if the index is available.
```

If Codex cannot see Kira tools:

- Confirm the JAR path exists.
- Confirm Java 21 is on `PATH`.
- Confirm the TOML table is named `mcp_servers` with an underscore.
- Restart Codex after editing config.

## 5. Suggested Codex Prompt

```text
Use Kira first. Search with keyword_search or discover_symbols before reading files.
If a symbol is found, call get_symbol. Only read full files after Kira identifies the path.
For code changes, keep edits small and run mvn test -q when practical.
```

For all Kira MCP services and exact call syntax, read:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

## References

- https://developers.openai.com/codex/guides/agents-md
- https://developers.openai.com/codex/config-reference
