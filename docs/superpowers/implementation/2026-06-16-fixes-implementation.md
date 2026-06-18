# Kira Fixes Implementation

Implemented from `docs/superpowers/plans/2026-06-16-fixes.md`.

Date: 2026-06-16

## Scope

This document records the implementation of the code-review correction plan for the Phase 0-4 Kira superpowers work. The fixes were applied at the repository root after moving `src/` and `pom.xml` to the root project layout.

## Completed Fixes

- Updated Lucene indexing to use `MMapDirectory`.
- Configured the Lucene writer with a 256 MB RAM buffer.
- Added path-based deletion through `LuceneIndexer.deleteByPath`.
- Renamed Git diff detection to `GitChangeDetector.detectChanges`.
- Added incremental indexing through `POST /api/v1/index/incremental`.
- Kept existing full indexing and full reindex endpoints.
- Updated single-file indexing to delete old chunks for the path before reindexing.
- Updated hybrid search to request a fixed candidate pool for BM25 and vector search before RRF.
- Replaced the basic OpenAPI parser with `swagger-parser`.
- Added OpenAPI operation chunks using type `OPENAPI_OP` and FQN format `METHOD /path`.
- Added Spring AI MCP server configuration.
- Annotated MCP methods with `@Tool` and `@ToolParam`.
- Renamed MCP tool methods to snake_case.
- Updated symbol graph responses to return signatures, JavaDoc, body text, caller signatures, and callee signatures.
- Updated graph callers, callees, and Kafka flow queries to return signatures rather than raw FQNs.
- Fixed reranker scoring for binary logits using the positive-negative logit difference.
- Fixed embedding normalization by applying the epsilon after square root.
- Fixed markdown heading chunk metadata so heading chunks do not duplicate the heading in `section`.
- Shortened markdown content hashes to 16 hex characters.
- Reworked `KuzuGraphStore` as an optional placeholder implementing `GraphStore`.
- Added graph configuration wiring for JGraphT and optional Kuzu selection.
- Expanded metrics to include indexed chunks, search requests, token return gauge, and timers.
- Implemented batched full repository reindexing.
- Updated the search API to use `mode` instead of the old `hybrid` boolean.
- Updated `SearchResponse` to include `hits` and `total`.
- Moved retrieval orchestration wiring into a dedicated Spring configuration class.

## Documentation Updates

- Updated `README.md` with root-project startup instructions.
- Added `docs/TUTORIAL.md`.
- Added `docs/SWAGGER.md`.
- Added IntelliJ HTTP examples in `http/kira-api.http`.
- Updated examples to use:
  - `"mode": "hybrid"`
  - `"mode": "bm25"`
  - `POST /api/v1/index/incremental`
  - `POST /api/v1/index/full`

## Tests Added Or Updated

- `LuceneIndexerTest`
- `ApiSpecParserTest`
- `MetricsServiceTest`
- `GraphQueriesTest`
- `MarkdownParserTest`
- `SearchControllerIntegrationTest`
- `HybridSearchTest`

## Verification

The implementation was verified with:

```bash
mvn compile -q
mvn test -q
mvn clean package -q
```

All tests passed.

The generated runnable artifact was:

```text
target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

Runtime smoke test command:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.embedding.model-path=/dev/null \
  --kira.reranker.enabled=false \
  --spring.ai.mcp.server.stdio=false \
  --server.port=18080
```

Health check:

```bash
curl -s http://localhost:18080/actuator/health
```

Result:

```json
{"status":"UP"}
```

## Test Summary

The final test run covered 22 tests:

- `MarkdownParserTest`: 3
- `TokenBudgetTest`: 1
- `ContextCompactorTest`: 1
- `MetricsServiceTest`: 1
- `NrtLuceneSearcherTest`: 1
- `ApiSpecParserTest`: 3
- `SearchControllerIntegrationTest`: 3
- `JavaSourceParserTest`: 2
- `GraphQueriesTest`: 3
- `HybridSearchTest`: 2
- `LuceneIndexerTest`: 1
- `CodeGraphStoreTest`: 1

## Dependency Note

The plan referenced `spring-ai-mcp-server-spring-boot-starter`, but that artifact did not resolve with the Spring AI `1.0.0` dependency set. The implementation uses the current Spring AI starter artifact:

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-server</artifactId>
</dependency>
```

This keeps the MCP server support aligned with the current Spring AI starter naming.

## Final Status

All planned fixes were implemented, documented, compiled, tested, packaged, and smoke-tested.
