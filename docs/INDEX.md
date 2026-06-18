# Kira Documentation Index

Use this index to choose the right document for the current task. This index covers the main user-facing docs and intentionally excludes `docs/superpowers`, which contains planning and implementation history.

## Start Here

| Goal | Read |
| --- | --- |
| Fast local walkthrough | [TUTORIAL.md](TUTORIAL.md) |
| Clean setup and performance tuning | [SETUP_AND_PERFORMANCE.md](SETUP_AND_PERFORMANCE.md) |
| Use Kira from agents or LLM workflows | [AGENT_INTEGRATION_GUIDE.md](AGENT_INTEGRATION_GUIDE.md) |
| Configure Codex, Claude, Gemini, GitHub Copilot, or OpenCode | [agent-clients/README.md](agent-clients/README.md) |
| Use Kira through MCP | [MCP_ENDPOINT_USAGE.md](MCP_ENDPOINT_USAGE.md) |
| See every MCP tool and how to call it | [MCP_KIRA_TOOL_REFERENCES.md](MCP_KIRA_TOOL_REFERENCES.md) |
| Index folders with PDFs, Word docs, Markdown, text, YAML, and JSON | [FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md](FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md) |
| Exclude unnecessary files before indexing | [FILE_EXCLUSION_GUIDE.md](FILE_EXCLUSION_GUIDE.md) |

## Documents

| Document | Purpose |
| --- | --- |
| [TUTORIAL.md](TUTORIAL.md) | Step-by-step local example: build, start, index one file, full index, search, answer context, graph endpoints, and reset. |
| [SETUP_AND_PERFORMANCE.md](SETUP_AND_PERFORMANCE.md) | Full setup guide with indexing, branch auto-sync, file acceptance filters, runtime data paths, and performance tuning. |
| [AGENT_INTEGRATION_GUIDE.md](AGENT_INTEGRATION_GUIDE.md) | Agent workflow guide for using Kira efficiently with search, symbols, graph tools, MCP, and token-saving patterns. |
| [agent-clients/README.md](agent-clients/README.md) | Index for configuring Codex, Claude Code, Gemini CLI, GitHub Copilot, and OpenCode with project instructions and Kira MCP. |
| [MCP_ENDPOINT_USAGE.md](MCP_ENDPOINT_USAGE.md) | MCP connection guide for stdio and HTTP/SSE, available tools, examples, and troubleshooting. |
| [MCP_KIRA_TOOL_REFERENCES.md](MCP_KIRA_TOOL_REFERENCES.md) | Complete reference of all Kira MCP services, parameters, return shapes, and call examples. |
| [FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md](FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md) | Tutorial for creating and indexing multi-level document folders for LLM and agent retrieval. |
| [FILE_EXCLUSION_GUIDE.md](FILE_EXCLUSION_GUIDE.md) | How to use `.gitignore`, global filters, and per-repo filters to keep noise and sensitive files out of the index. |
| [RESOURCE_SIZING.md](RESOURCE_SIZING.md) | CPU, RAM, disk, indexing-time, and JVM sizing guidance by repo size. |
| [SWAGGER.md](SWAGGER.md) | Swagger UI and OpenAPI usage, request shapes, and API documentation configuration. |

## Common Workflows

### Index This Repo

1. Read [TUTORIAL.md](TUTORIAL.md) for the copy/paste walkthrough.
2. Read [FILE_EXCLUSION_GUIDE.md](FILE_EXCLUSION_GUIDE.md) before indexing if you want to skip tests, generated files, local secrets, or `docs/superpowers`.
3. Read [SETUP_AND_PERFORMANCE.md](SETUP_AND_PERFORMANCE.md) if you want branch auto-sync or performance tuning.

### Use Kira With An Agent

1. Read [AGENT_INTEGRATION_GUIDE.md](AGENT_INTEGRATION_GUIDE.md).
2. Read [agent-clients/README.md](agent-clients/README.md) for client-specific setup.
3. Read [MCP_ENDPOINT_USAGE.md](MCP_ENDPOINT_USAGE.md) if the agent supports MCP.
4. Use `search_code`, `search_knowledge`, `keyword_search`, `discover_symbols`, `get_symbol`, and `answer_context` based on the task.

### Index Documents For An LLM

1. Read [FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md](FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md).
2. Export spreadsheet content to Markdown, text, or JSON companion files.
3. Use [FILE_EXCLUSION_GUIDE.md](FILE_EXCLUSION_GUIDE.md) to skip private, temporary, archive, and generated files.

### Size A Machine

1. Read [RESOURCE_SIZING.md](RESOURCE_SIZING.md).
2. Count indexable files after exclusions.
3. Apply the JVM heap and indexing-thread settings for your repo size.

## Quick Commands

Index one file:

```bash
curl -X POST http://localhost:8080/api/v1/index \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "path": "README.md",
    "gitSha": "local",
    "branch": "main"
  }'
```

Index a full repo:

```bash
curl -X POST http://localhost:8080/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "gitSha": "local",
    "branch": "main"
  }'
```

Search:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how to start the service",
    "repo": "kira",
    "branch": "main",
    "k": 5,
    "mode": "hybrid"
  }'
```

Generate compact context:

```bash
curl -X POST http://localhost:8080/api/v1/answer-context \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how does Kira index and search files",
    "repo": "kira",
    "budgetTokens": 1200
  }'
```
