# Copilot Instructions For Kira

Use Kira MCP tools as the first source of repository context before reading files directly.

For every Kira MCP service and exact call examples, read `docs/MCP_KIRA_TOOL_REFERENCES.md`.

## Context Workflow

1. Call `index_status()` to confirm Kira has indexed data.
2. For exact names, identifiers, routes, or config keys, call `keyword_search(query, repo="kira", domain="CODE", k=5)`.
3. For code questions, call `search_code(query, repo="kira", branch="main", k=5)`.
4. For documentation questions, call `search_knowledge(query, repo="kira", branch="main", k=5)`.
5. For mixed code and documentation questions, call `semantic_search(query, repo="kira", branch="main", k=5)`.
6. If only symbol metadata is needed, call `discover_symbols(partialName, type, k=10)`.
7. If the exact symbol name is unknown and snippets are useful, call `find_symbol(partialName, type)` before opening files.
8. If an exact FQN is available, call `get_symbol(fqn)` before reading the source file.
9. For endpoint questions, prefer `get_endpoint(method, path)` or `check_spec_vs_impl(repo="kira")`.
10. For graph questions, use `get_callers(fqn, depth)`, `get_callees(fqn, depth)`, or `expand_context(fqns, hops, maxResults)`.
11. For Spring dependency questions, use `get_bean_graph(name, depth)`.
12. For doc/code cross-lookup, use `get_design_for_symbol(fqn)` or `get_code_for_doc(docId)`.
13. Read files directly only when Kira results are missing, stale, incomplete, or when exact line-level edits are required.

## Result Handling

- Check returned `error` fields before trusting empty MCP results.
- Treat empty `hits` or `symbols` with `error=null` as a normal no-match result.
- If Kira appears stale, ask the user before triggering a full reindex.
- Prefer focused Kira queries with `repo` and `branch` filters over broad workspace searches.

## Editing Guidance

- Use Kira for discovery and impact analysis.
- Use direct file reads for final implementation details and exact edits.
- After changing code or docs, suggest indexing only changed files or using incremental indexing instead of full reindex when possible.
