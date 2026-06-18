# MCP Documentation Refresh

Source work:

- `docs/superpowers/plans/2026-06-17-mcp-gaps.md`
- `docs/superpowers/plans/2026-06-17-mcp-bugfixes.md`
- `docs/superpowers/implementation/2026-06-17-mcp-gaps-implementation.md`
- `docs/superpowers/implementation/2026-06-17-mcp-bugfixes-implementation.md`

## Updated Documents

- `docs/MCP_ENDPOINT_USAGE.md`
  - Updated the available MCP tool table from the older 10-tool set to the then-current 17-tool set.
  - Later search/token work added `keyword_search` and `discover_symbols`, bringing the current MCP tool count to 19.
  - Added examples for `find_symbol`, `get_endpoint`, and `check_spec_vs_impl`.
  - Documented `SearchResult`, `SymbolListResult`, and `SpecImplReport` error fields.
  - Added repo-scoped endpoint comparison guidance.

- `docs/AGENT_INTEGRATION_GUIDE.md`
  - Updated search tools to return `SearchResult` instead of raw hit lists.
  - Added tool reference entries for `find_symbol`, `refresh_index`, `get_endpoint`, `get_bean_graph`, `get_design_for_symbol`, `get_code_for_doc`, and `check_spec_vs_impl`.
  - Updated the decision tree to use `find_symbol` before `get_symbol` when the exact FQN is unknown.
  - Replaced the manual API spec comparison scenario with the direct `check_spec_vs_impl` workflow.
  - Added the newer MCP tools to the quick reference card.

- `docs/FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md`
  - Added MCP guidance for `get_design_for_symbol`, `get_code_for_doc`, and `refresh_index`.
  - Added error-field handling guidance for search-style MCP tools.
  - Clarified that one Kira service can handle multiple repos or document folders when they share the same data directory.

- `README.md`
  - Added a feature bullet summarizing the current MCP tool coverage.

## Current MCP Return Shapes

Search-style tools:

```json
{
  "hits": [],
  "error": null
}
```

Symbol discovery:

```json
{
  "symbols": [],
  "error": null
}
```

Spec-vs-implementation comparison:

```json
{
  "repo": "backend-api",
  "unimplemented": [],
  "undocumented": [],
  "matched": [],
  "error": null
}
```

## Verification

Documentation was checked with targeted `rg` scans for stale user-facing references to the old MCP tool count and raw MCP search return types.

No Java code changed in this refresh, so Maven tests were not rerun for this documentation-only update.
