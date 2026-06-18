# MCP Gaps Implementation Report

Source plan: `docs/superpowers/plans/2026-06-17-mcp-gaps.md`

## Implemented

- Added safe MCP search return shape through `SearchResult`.
- Added symbol discovery return shape through `SymbolRef`.
- Added Lucene lookup helpers for exact FQN body lookup, FQN fragment search, title lookup by id, and type/repo search.
- Added `RetrievalOrchestrator` methods for symbol body lookup, symbol discovery, design-doc lookup, code-for-doc lookup, and OpenAPI spec vs implementation comparison.
- Updated MCP search tools to use reranked hybrid search and return `SearchResult` with an optional error string instead of throwing tool exceptions.
- Enriched `get_symbol` with full symbol body text from the Lucene index when graph metadata does not already contain a body.
- Added MCP `find_symbol` for partial symbol discovery before calling `get_symbol`.
- Added MCP `refresh_index` for blocking full repository reindex from an MCP client.
- Added bean dependency DTOs: `BeanDep` and `BeanGraph`.
- Added `SpecImplReport` for endpoint spec/implementation comparison results.
- Updated Java parsing so Spring mapping annotations emit endpoint keys in `METHOD /path` form, for example `POST /api/pay`.
- Updated Java parsing so constructor-injected bean-like parameters emit `DEPENDS_ON` graph edges.
- Added graph queries for endpoint lookup, bean dependency traversal, and implemented endpoint key collection.
- Injected `GraphQueries` into `RetrievalOrchestrator` so retrieval can combine Lucene search results with graph facts.
- Added five Wave 2 MCP tools for endpoint, bean graph, design/code cross-lookup, and spec-vs-implementation checks.
- Updated MCP registration tests to assert all 17 tools from this phase were exposed.
- Later search/token work added `keyword_search` and `discover_symbols`, bringing the current MCP tool count to 19.

## Exposed MCP Tools

- `search_code`
- `search_knowledge`
- `semantic_search`
- `answer_context`
- `get_symbol`
- `find_symbol`
- `get_callers`
- `get_callees`
- `get_kafka_flow`
- `expand_context`
- `refresh_index`
- `get_endpoint`
- `get_bean_graph`
- `get_design_for_symbol`
- `get_code_for_doc`
- `check_spec_vs_impl`
- `index_status`

## Key Files

- `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- `src/main/java/com/acme/airetrieval/config/RetrievalConfig.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/SearchResult.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/SymbolRef.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/BeanDep.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/BeanGraph.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java`

## Tests Added Or Updated

- `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`
- `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`
- `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`
- `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`
- `src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java`

## Verification

Run from the project root:

```bash
mvn test -q
```

Expected result: all tests pass.

Smoke-test server startup with an isolated index directory:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--kira.index-dir=/tmp/kira-smoke-mcp-gaps
curl -s http://localhost:8080/actuator/health
```

Expected health response:

```json
{"status":"UP"}
```

## Completed Verification On 2026-06-17

- `mvn test -q` passed.
- Spring MCP registration reported 17 registered tools during this phase. Current registration after the search/token gaps implementation is 19 tools.
- `mvn spring-boot:run` with a temporary index directory started successfully.
- `curl -s http://localhost:8080/actuator/health` returned `{"status":"UP"}`.

The default startup path first failed because the normal Lucene index was locked at `/home/user/.kira/data/lucene/write.lock`. Final startup verification used an isolated temporary index directory to avoid touching the live local index.

## Notes

- The workspace root was not a git repository during implementation, so plan commit steps were not run.
- Plan steps that only verified expected pre-implementation failures were not marked complete because implementation was applied directly before those failure checks.
