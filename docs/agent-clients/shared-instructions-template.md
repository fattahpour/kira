# Shared Agent Instructions Template

Copy the relevant sections into `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `.github/copilot-instructions.md`, or an OpenCode instruction file.

## Project

Kira is a Java 21 / Spring Boot retrieval service. It indexes code and knowledge files into Lucene, builds Java code graph metadata, and exposes search through REST plus MCP tools.

## Core Rules

- Prefer existing project patterns over new abstractions.
- Keep changes scoped to the requested behavior.
- Do not add external services or databases for retrieval.
- Do not commit secrets, model files, local runtime data, or generated build output.
- Update docs when behavior or user workflow changes.

## Build And Test

Use Maven from the repo root:

```bash
mvn test -q
```

For a compile-only check:

```bash
mvn test -q -DskipTests
```

## Kira-First Search Workflow

Before reading many files, use Kira:

1. For exact names, call `keyword_search`.
2. For symbol names, call `discover_symbols` or `find_symbol`.
3. For one known Java symbol, call `get_symbol`.
4. For code neighborhoods, call `get_callers`, `get_callees`, or `expand_context`.
5. For final compact context, call `answer_context`.

Only read full files after Kira identifies the relevant paths or FQNs.

## MCP Tools To Prefer

Use `docs/MCP_KIRA_TOOL_REFERENCES.md` for the complete service reference and call examples.

Available Kira MCP services:

- `index_status()`
- `search_code(query, repo, branch, k)`
- `search_knowledge(query, repo, branch, k)`
- `semantic_search(query, repo, branch, k)`
- `keyword_search(query, repo, domain, k)`
- `answer_context(query, repo, budgetTokens)`
- `find_symbol(partialName, type)`
- `discover_symbols(partialName, type, k)`
- `get_symbol(fqn)`
- `get_callers(fqn, depth)`
- `get_callees(fqn, depth)`
- `expand_context(fqns, hops, maxResults)`
- `get_kafka_flow(topic)`
- `get_endpoint(method, path)`
- `get_bean_graph(name, depth)`
- `get_design_for_symbol(fqn)`
- `get_code_for_doc(docId)`
- `check_spec_vs_impl(repo)`
- `refresh_index(repo, repoDir)`

Prefer `keyword_search`, `discover_symbols`, `get_symbol`, and `answer_context` for low-token workflows.

## File Exclusion

Do not index or inspect unnecessary files unless the task requires it:

- `target/**`
- `build/**`
- `out/**`
- `.git/**`
- `.idea/**`
- `.vscode/**`
- `node_modules/**`
- `*.class`
- `*.jar`
- `*.war`
- `*.log`
- `.env*`

Use `docs/FILE_EXCLUSION_GUIDE.md` for details.

## Useful Docs

- `docs/INDEX.md`
- `docs/TUTORIAL.md`
- `docs/MCP_ENDPOINT_USAGE.md`
- `docs/MCP_KIRA_TOOL_REFERENCES.md`
- `docs/AGENT_INTEGRATION_GUIDE.md`
- `docs/FILE_EXCLUSION_GUIDE.md`
- `docs/agent-clients/README.md`
