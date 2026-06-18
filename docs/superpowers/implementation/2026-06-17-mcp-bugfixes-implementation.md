# MCP Bugfixes Implementation Report

Source plan: `docs/superpowers/plans/2026-06-17-mcp-bugfixes.md`

## Implemented

- Fixed Spring mapping array annotation parsing so `@GetMapping({"/v1", "/v2"})` emits only the first endpoint key instead of a malformed combined value.
- Tagged parsed REST endpoint graph nodes with `REPO:<repo>` so repo-scoped spec/implementation comparison does not mix endpoints from other repositories.
- Updated `RetrievalOrchestrator.checkSpecVsImpl(repo)` to compare OpenAPI operations against implemented endpoint keys scoped to the same repo.
- Fixed bean dependency graph traversal beyond depth 1 by resolving simple constructor dependency names back to BEAN-tagged FQN nodes.
- Removed the hardcoded `domain=CODE` filter from symbol fragment search so non-code chunks such as OpenAPI operation chunks can be discovered.
- Added an indexed `fqn_simple` field and changed symbol lookup from a leading wildcard query to a faster lowercase prefix query.
- Added `SymbolListResult` so `find_symbol` can distinguish an empty successful result from an index/search failure.
- Added an `error` field to `SpecImplReport` so `check_spec_vs_impl` can report exceptions instead of returning an ambiguous empty report.
- Updated tests for parser, graph query, Lucene search, and MCP tool behavior.

## Behavior Changes

- `find_symbol` now returns:

```json
{
  "symbols": [],
  "error": null
}
```

On failure, `symbols` is empty and `error` contains the exception message.

- `check_spec_vs_impl` now returns:

```json
{
  "repo": "myrepo",
  "unimplemented": [],
  "undocumented": [],
  "matched": [],
  "error": null
}
```

On failure, `error` contains the exception message.

- Endpoint keys emitted from Java parser remain in `METHOD /path` form, for example `GET /v1/items`, but endpoint nodes now also carry repo tags.
- Symbol prefix search is optimized for common simple-name lookups such as `paymentservice` -> `com.acme.PaymentService`.

## Key Files

- `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java`
- `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/SymbolListResult.java`
- `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java`

## Tests Added Or Updated

- `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`
- `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`
- `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`
- `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

## Verification

Run from the project root:

```bash
mvn test -q
```

Expected result: all tests pass.

Smoke-test server startup with an isolated index directory:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--kira.index-dir=/tmp/kira-smoke-bugfix
curl -s http://localhost:8080/actuator/health
```

Expected health response:

```json
{"status":"UP"}
```

## Completed Verification On 2026-06-17

- `mvn test -pl . -Dtest=JavaSourceParserTest,GraphQueriesTest -q` passed.
- `mvn test -pl . -Dtest=NrtLuceneSearcherTest,McpToolsTest,McpToolsRegistrationTest -q` passed.
- `mvn test -q` passed.
- Spring MCP registration still reported 17 registered tools during this bug-fix phase. Current registration after the search/token gaps implementation is 19 tools.
- `mvn spring-boot:run` with `/tmp/kira-smoke-bugfix` started successfully.
- `curl -s http://localhost:8080/actuator/health` returned `{"status":"UP"}`.

## Notes

- The workspace root was not a git repository during implementation, so plan commit steps were not run.
- Plan steps that only verified expected pre-implementation failures were not marked complete because implementation was applied directly before those failure checks.
- `LuceneIndexer` derives `fqn_simple` from `.`, `#`, or `/` separators so both Java symbols and endpoint-style FQNs can be searched efficiently.
