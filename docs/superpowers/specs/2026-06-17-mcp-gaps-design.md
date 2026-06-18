# MCP Gaps Design — Kira

**Date:** 2026-06-17  
**Scope:** Gap analysis of existing MCP tools vs CLAUDE.md spec and real-world best practices. Two-wave implementation plan for Codex.

---

## Gap Analysis

### Implemented (10 tools — baseline)

`search_code`, `search_knowledge`, `semantic_search`, `answer_context`, `get_symbol`, `get_callers`, `get_callees`, `get_kafka_flow`, `expand_context`, `index_status`

### Wave 1 Gaps — Robustness + Quality (5 items)

| # | Gap | Root cause |
|---|-----|-----------|
| W1-1 | All `@Tool` methods throw raw `Exception` | MCP session crashes on any failure; agent loses context |
| W1-2 | `get_symbol.body` always `null` | `GraphQueries.getSymbolView` never queries Lucene for stored method text |
| W1-3 | No FQN discovery tool | Agents must guess exact FQN; `get_symbol("PaymentService")` returns nothing |
| W1-4 | `search_code`/`search_knowledge` skip reranker | Returns un-reranked candidates; `answer_context` reranks but raw search doesn't |
| W1-5 | No `refresh_index` via MCP | Agents can trigger reindex only via REST; MCP-native workflows can't self-heal |

### Wave 2 Gaps — Missing Planned Tools (5 items)

| # | Tool | Status |
|---|------|--------|
| W2-1 | `get_endpoint(method, path)` | Not implemented; planned in CLAUDE.md |
| W2-2 | `get_bean_graph(name)` | Not implemented; planned in CLAUDE.md |
| W2-3 | `get_design_for_symbol(fqn)` | Not implemented; planned in CLAUDE.md |
| W2-4 | `get_code_for_doc(docId)` | Not implemented; planned in CLAUDE.md |
| W2-5 | `check_spec_vs_impl` | Not implemented; planned in CLAUDE.md |

---

## Architecture

### Wave 1 — Design

**W1-1: Error handling**

Introduce a `McpError` sealed response type usable in all tools:

```java
public record McpError(String tool, String message) {}
```

Use a typed wrapper for all tools that can fail:
```java
record SearchResult(List<SearchHit> hits, String error) {
    static SearchResult ok(List<SearchHit> hits) { return new SearchResult(hits, null); }
    static SearchResult err(String msg) { return new SearchResult(List.of(), msg); }
}
```

Every `@Tool` returning a list wraps in try/catch and returns `SearchResult`. Tools returning a single object (`SymbolView`, `KafkaFlow`, etc.) return `null` on not-found and a dedicated `ErrorResponse` record on unexpected failure. Agents always receive a typed, non-throwing response.

**W1-2: `get_symbol` body lookup**

Add `NrtLuceneSearcher.findByFqn(String fqn): Optional<String>` — term query on `fqn` field, return stored `text` field. Called from `GraphQueries.getSymbolView` after graph lookup; body field populated when Lucene hit found.

**W1-3: `find_symbol` tool**

New `@Tool` in `McpTools`:
```
find_symbol(partialName, type?)
```
- `partialName`: class name, method name, or partial FQN
- `type`: optional filter — `CLASS`, `METHOD`, `INTERFACE`, `ENDPOINT`
- Returns: `List<SymbolRef>` — `{fqn, signature, type, path}`
- Backed by: `NrtLuceneSearcher.searchByNameFragment(partialName, type, k)` — wildcard/fuzzy on `fqn` field + optional `type` filter

**W1-4: Reranking in search tools**

`McpTools.search_code` and `McpTools.search_knowledge`: replace `retrieval.hybrid(...)` with `retrieval.hybridRerank(...)`. No interface changes needed — `hybridRerank` already exists in `RetrievalOrchestrator`.

**W1-5: `refresh_index` tool**

New `@Tool`:
```
refresh_index(repo, repoDir)
```
- Calls `FullReindexService.reindex(repo, repoDir)` async
- Returns `IndexStatus` with `{repo, status: "indexing_started", docCount}`
- Inject `FullReindexService` into `McpTools`

---

### Wave 2 — Design

**W2-1: `get_endpoint(method, path)`**

Graph lookup: find node with tag `ENDPOINT` whose signature contains `method` (GET/POST/etc.) and `path`. Backed by `GraphQueries.getEndpoint(String httpMethod, String path)` — linear scan of ENDPOINT-tagged nodes matching both predicates. Returns `EndpointInfo` (already exists in `ingest/model`).

**W2-2: `get_bean_graph(name)`**

BFS from BEAN node on `DEPENDS_ON` edges. New `GraphQueries.getBeanGraph(String name, int depth)` returns `BeanGraph` DTO:
```java
record BeanGraph(String root, List<BeanDep> dependencies) {}
record BeanDep(String beanFqn, String signature, int depth) {}
```
`depth` defaults to 2 in the tool. Limits result to 50 nodes.

**W2-3: `get_design_for_symbol(fqn)`**

Fallback strategy (no cross-domain edges yet): call `retrieval.hybrid(fqn, KNOWLEDGE filter, k=5)`. When cross-domain DESCRIBES edges exist (Phase 4), upgrade to graph traversal. Returns `List<SearchHit>` filtered to KNOWLEDGE domain.

**W2-4: `get_code_for_doc(docId)`**

Fallback: parse the doc's title/section from Lucene by id, then call `retrieval.hybrid(title, CODE filter, k=5)`. Phase 4 upgrade: traverse MENTIONS edges. Returns `List<SearchHit>` filtered to CODE domain.

**W2-5: `check_spec_vs_impl`**

Compare two sets:
- **Spec endpoints**: Lucene query for chunks with `type=ENDPOINT` and `domain=KNOWLEDGE` (from `ApiSpecParser`) for a given `repo`
- **Impl endpoints**: `CodeGraphStore.getNodesByTag("ENDPOINT")` for the same repo

Diff: spec endpoints missing from impl = unimplemented; impl endpoints missing from spec = undocumented.

```java
record SpecImplReport(
    String repo,
    List<String> unimplemented,   // in spec, not in graph
    List<String> undocumented,    // in graph, not in spec
    List<String> matched          // in both
) {}
```

New `RetrievalOrchestrator.checkSpecVsImpl(String repo)` — orchestrates both sources (graph + index); `GraphQueries` stays framework-free. `McpTools` calls it like any other retrieval method.

---

## File Impact

### Wave 1

| File | Change |
|------|--------|
| `McpTools.java` | Error handling wrapper; replace hybrid→hybridRerank; add `find_symbol`, `refresh_index` tools |
| `NrtLuceneSearcher.java` | Add `findByFqn(fqn)`, `searchByNameFragment(partial, type, k)` |
| `GraphQueries.java` | `getSymbolView` fetches body from Lucene |
| `retrieve/dto/SymbolRef.java` | New DTO for `find_symbol` results |

### Wave 2

| File | Change |
|------|--------|
| `McpTools.java` | Add 5 new `@Tool` methods |
| `GraphQueries.java` | Add `getEndpoint`, `getBeanGraph`, `getDesignDocs`, `getCodeForDoc` |
| `RetrievalOrchestrator.java` | Add `checkSpecVsImpl(repo)` — cross-layer diff (graph + index) |
| `retrieve/dto/BeanGraph.java` | New DTO |
| `retrieve/dto/BeanDep.java` | New DTO |
| `retrieve/dto/SpecImplReport.java` | New DTO |
| `retrieve/dto/SymbolRef.java` | New DTO (Wave 1, reused in Wave 2) |

---

## Constraints

- No Python, no external DB, no network calls at runtime (from CLAUDE.md)
- `GraphQueries` and `NrtLuceneSearcher` must stay framework-free — only `McpTools` touches Spring AI
- `get_design_for_symbol` and `get_code_for_doc` use search fallback until Phase 4 cross-domain edges
- Wave 1 must not break any existing tests

---

## Success Criteria

**Wave 1:**
- All `@Tool` methods return typed errors instead of throwing
- `get_symbol` returns method body for indexed Java methods
- `find_symbol("PaymentService")` returns at least one result after indexing
- `search_code` results match `answer_context` quality (same reranker path)
- `refresh_index("kira", "/path")` triggers reindex and returns status

**Wave 2:**
- `get_endpoint("POST", "/api/v1/search")` returns endpoint info
- `get_bean_graph("RetrievalOrchestrator")` returns dependency tree
- `get_design_for_symbol(fqn)` returns relevant knowledge chunks
- `get_code_for_doc(docId)` returns relevant code chunks
- `check_spec_vs_impl("kira")` returns diff report (may be all-unimplemented if no OpenAPI spec indexed)
