# Gemini Instructions For Kira

Use Kira MCP tools before broad file reads.

For the complete MCP service catalog and call examples, read:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

Recommended workflow:

1. Call `index_status()` before debugging missing search results.
2. Use `keyword_search(query, repo, domain, k)` for exact identifiers, routes, config keys, and class names.
3. Use `discover_symbols(partialName, type, k)` or `find_symbol(partialName, type)` before `get_symbol(fqn)`.
4. Use `search_code(query, repo, branch, k)` for code questions.
5. Use `search_knowledge(query, repo, branch, k)` for documentation questions.
6. Use `semantic_search(query, repo, branch, k)` only when both code and docs may matter.
7. Use `answer_context(query, repo, budgetTokens)` for compact final context.
8. Use graph tools such as `get_callers`, `get_callees`, and `expand_context` for impact analysis.

Read full files only after Kira identifies the relevant path or symbol.
