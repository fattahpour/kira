# Search Token Gaps Implementation Report

Source plan: `docs/superpowers/plans/2026-06-17-search-token-gaps.md`

## Implemented

- Added repo tags to OpenAPI endpoint graph nodes.
- Enriched OpenAPI chunks with descriptions, parameters, request bodies, and responses.
- Split oversized Markdown sections into paragraph-bound sub-chunks.
- Populated Java annotation symbols and indexed them as Lucene `symbol` filters.
- Increased snippets to 500 characters and truncated on word boundaries.
- Made hybrid candidate count configurable with `kira.candidate-k`.
- Used reranked cross-domain lookup for design/code links and simpler symbol-name queries.
- Added configurable `kira.spec-max-ops` and `SpecImplReport.total`.
- Added `keyword_search` BM25-only MCP tool.
- Added `discover_symbols` metadata-only MCP tool.
- Added `expand_context(..., maxResults)` cap.
- Reserved minimum per-hit budget in context compaction.
- Capped `get_symbol` body enrichment at 8000 characters.
- Updated related MCP, agent, document, README, and Copilot instructions.

## Key Files

- `src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java`
- `src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java`
- `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java`
- `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- `src/main/java/com/acme/airetrieval/index/HybridSearch.java`
- `src/main/java/com/acme/airetrieval/index/model/SearchFilter.java`
- `src/main/java/com/acme/airetrieval/index/model/SearchHit.java`
- `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- `src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java`
- `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- `src/main/resources/application.yml`

## Tests Added or Updated

- `src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java`
- `src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java`
- `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`
- `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`
- `src/test/java/com/acme/airetrieval/index/HybridSearchTest.java`
- `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`
- `src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java`
- `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`
- `src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java`

## Verification

- `mvn test -q`
- Result: 92 tests, 0 failures, 0 errors, 0 skipped.
- Spring MCP registration reported 19 tools.

## Deviations From Plan

- No commits were created because this workspace is not a Git repository.
- Tests were added directly against the current codebase shape where method or record accessor names differed from the draft plan.
