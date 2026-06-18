# Configure GitHub Copilot For Kira

This guide shows how to configure GitHub Copilot Chat, Copilot Agent mode, and VS Code MCP usage for this repo.

## 1. Repository Instructions

GitHub Copilot reads repository custom instructions from:

```text
.github/copilot-instructions.md
```

This repo already has that file. It tells Copilot to use Kira MCP tools before broad file reads.

For the complete Kira MCP service list and call examples, point Copilot at:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

Recommended structure:

```markdown
# Copilot Instructions For Kira

Use Kira MCP tools as the first source of repository context before reading files directly.

## Context Workflow

1. Call `index_status()` to confirm Kira has indexed data.
2. For exact names, identifiers, routes, or config keys, call `keyword_search`.
3. For code questions, call `search_code`.
4. For documentation questions, call `search_knowledge`.
5. If an exact FQN is available, call `get_symbol`.
6. For the full MCP tool reference, read `docs/MCP_KIRA_TOOL_REFERENCES.md`.
7. Read files directly only when Kira results are missing, stale, incomplete, or exact line-level edits are required.
```

Keep this file short. Put large explanations in normal docs and link to them.

## 2. Path-Specific Instructions

For instructions that should apply only to part of the repo, use:

```text
.github/instructions/*.instructions.md
```

Example:

```text
.github/instructions/java.instructions.md
.github/instructions/docs.instructions.md
```

Example Java instruction file:

```markdown
---
applyTo: "src/**/*.java"
---

# Java Instructions

- Follow the existing Spring Boot patterns.
- Keep retrieval engine packages framework-light unless the package already uses Spring.
- Run `mvn test -q` after behavior changes when practical.
```

Example docs instruction file:

```markdown
---
applyTo: "docs/**/*.md"
---

# Documentation Instructions

- Keep examples copy/pasteable.
- Use fake paths such as `/home/example/projects/kira`.
- Link to `docs/INDEX.md` when adding new user-facing docs.
```

Use path-specific instructions when the guidance would be too noisy for every Copilot request.

## 3. Personal Or Workspace Instructions In VS Code

In VS Code, use the command palette:

```text
Chat: Configure Instructions
```

Use this for personal preferences that should not be committed, such as response style or private environment assumptions.

For shared repo behavior, prefer committed files:

- `.github/copilot-instructions.md`
- `.github/instructions/*.instructions.md`

## 4. Configure Kira MCP In VS Code

Copilot in VS Code can use MCP servers. Use HTTP/SSE when you want one running Kira service shared by VS Code and other clients.

Start Kira:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --spring.ai.mcp.server.stdio=false \
  --spring.ai.mcp.server.type=ASYNC \
  --server.port=8080
```

Then add an MCP server in VS Code with the command palette:

```text
MCP: Add Server
```

Use this URL:

```text
http://localhost:8080/sse
```

If your VS Code setup uses workspace MCP configuration, add a server equivalent to:

```json
{
  "mcpServers": {
    "kira": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

The repo also has `.mcp.json` with `kira-stdio` and `kira-http` examples that other MCP-compatible clients can reuse.

## 5. Verify In Copilot Chat

Open Copilot Chat in VS Code Agent mode and ask:

```text
Use Kira MCP. Call index_status, then keyword_search for RetrievalOrchestrator in repo kira.
```

Expected behavior:

1. Copilot checks whether Kira has an index.
2. Copilot searches Kira before broad file reads.
3. Copilot reads direct files only after it has a likely path or symbol.

## 6. Suggested Copilot Prompts

Find a method:

```text
Use Kira first. Find the method that handles hybrid search and show me the file path and symbol.
```

Understand a doc:

```text
Use Kira search_knowledge in repo kira. Find the guide for excluding unnecessary files from indexing.
```

Edit after search:

```text
Use Kira to locate the related docs first, then update only the relevant file.
```

## 7. Troubleshooting

Copilot ignores repository instructions:

- Confirm `.github/copilot-instructions.md` is at the repo root.
- Confirm Copilot Chat custom instructions are enabled in your editor.
- Restart VS Code after adding instruction files.
- Keep instructions direct and short.

Kira MCP tools are unavailable:

- Confirm Kira is running on `http://localhost:8080/sse`.
- Confirm MCP is enabled for your GitHub Copilot plan or organization policy.
- Confirm VS Code lists the Kira MCP server.
- Check `curl http://localhost:8080/actuator/health`.

Search returns nothing:

- Ask Copilot to call `index_status()`.
- Confirm the repo was indexed as `repo="kira"`.
- Confirm the same branch value is used for indexing and search.
- Check `docs/FILE_EXCLUSION_GUIDE.md` if a file may be excluded.

## References

- https://docs.github.com/copilot/customizing-copilot/adding-custom-instructions-for-github-copilot
- https://code.visualstudio.com/docs/copilot/customization/custom-instructions
- https://code.visualstudio.com/docs/agent-customization/mcp-servers
- https://docs.github.com/en/copilot/concepts/context/mcp
