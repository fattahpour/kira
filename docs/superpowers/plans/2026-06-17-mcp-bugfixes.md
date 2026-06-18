# MCP Bug-Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix seven confirmed bugs found in the MCP gaps implementation: malformed endpoint keys from array annotations, un-scoped endpoint key comparison, broken bean-graph depth traversal, hardcoded domain filter in symbol search, slow leading-wildcard query, and missing error signals in two MCP tools.

**Architecture:** Each bug is fixed independently in TDD order — test first, then minimal implementation. Bugs 1–3 are in the parse/graph layer; Bugs 4–5 are in the Lucene index layer; Bugs 6–7 are in the MCP tool layer. No structural refactoring; only targeted fixes.

**Tech Stack:** Java 21, Spring Boot 3.5, Apache Lucene 10, JavaParser, JUnit 5, AssertJ, Mockito.

---

## Files Changed

| File | Bug(s) |
|---|---|
| `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java` | 1, 2 |
| `src/main/java/com/acme/airetrieval/graph/GraphQueries.java` | 2, 3 |
| `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java` | 2 |
| `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java` | 5 |
| `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java` | 4, 5 |
| `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java` | 7 |
| `src/main/java/com/acme/airetrieval/mcp/McpTools.java` | 6, 7 |
| `src/main/java/com/acme/airetrieval/retrieve/dto/SymbolListResult.java` | 6 (new) |
| `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java` | 1 |
| `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java` | 2, 3 |
| `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java` | 4, 5 |
| `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java` | 6, 7 |

---

## Task 1: Fix array annotation values in JavaSourceParser

**Bug:** `@GetMapping({"/v1","/v2"})` → `strip()` removes outer `{"` and `"}` but leaves interior `", "/v2` → endpoint key becomes `GET /v1", "/v2` — malformed, never matches spec keys.

**Fix:** In `endpoint()`, detect `ArrayInitializerExpr` and take only the first element before calling `strip()`.

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- Test: `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`

- [x] **Step 1: Write the failing test**

Add to `JavaSourceParserTest.java`:

```java
@Test
void parse_getMappingWithArrayPaths_usesFirstPath() {
    String source = """
        package com.acme;
        import org.springframework.web.bind.annotation.*;
        @RestController
        public class ItemController {
            @GetMapping({"/v1/items", "/v2/items"})
            public String list() { return "ok"; }
        }
        """;
    var result = parser.parse("repo", "src/ItemController.java", "sha", source);

    assertThat(result.events()).anySatisfy(event -> {
        assertThat(event).isInstanceOf(GraphEvent.NodeEvent.class);
        var node = (GraphEvent.NodeEvent) event;
        assertThat(node.id()).isEqualTo("GET /v1/items");
        assertThat(node.tags()).contains("ENDPOINT");
    });

    assertThat(result.events()).noneMatch(event ->
        event instanceof GraphEvent.NodeEvent n && n.id().contains("\","));
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd <kira-install-dir>
mvn test -pl . -Dtest=JavaSourceParserTest#parse_getMappingWithArrayPaths_usesFirstPath -q
```

Expected: FAIL — node id contains `"` or `,`.

- [x] **Step 3: Add import and fix `endpoint()` in JavaSourceParser**

Add this import to `JavaSourceParser.java` (with the other `com.github.javaparser.ast.expr.*` imports):

```java
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
```

Replace the `endpoint()` method (lines 106–116) with:

```java
private static String endpoint(AnnotationExpr annotation) {
    if (annotation instanceof SingleMemberAnnotationExpr single) {
        var val = single.getMemberValue();
        if (val instanceof ArrayInitializerExpr arr)
            return arr.getValues().isEmpty() ? "/" : strip(arr.getValues().get(0).toString());
        return strip(val.toString());
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
        for (MemberValuePair pair : normal.getPairs()) {
            if ("value".equals(pair.getNameAsString())
                    || "path".equals(pair.getNameAsString())
                    || "topics".equals(pair.getNameAsString())) {
                var val = pair.getValue();
                if (val instanceof ArrayInitializerExpr arr)
                    return arr.getValues().isEmpty() ? "/" : strip(arr.getValues().get(0).toString());
                return strip(val.toString());
            }
        }
    }
    return annotation.getNameAsString();
}
```

`strip()` is unchanged.

- [x] **Step 4: Run the new test and the full parser test suite**

```bash
mvn test -pl . -Dtest=JavaSourceParserTest -q
```

Expected: all tests PASS including `parse_getMappingWithArrayPaths_usesFirstPath`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java
git commit -m "fix: handle array annotation values in JavaSourceParser endpoint extraction"
```

---

## Task 2: Scope getEndpointKeys() to repo

**Bug:** `RetrievalOrchestrator.checkSpecVsImpl(repo)` scopes spec keys to `repo` via Lucene, but `graphQueries.getEndpointKeys()` returns endpoints from ALL repos — every endpoint in every other indexed repo appears as "undocumented" in the report.

**Fix:** Tag each endpoint node with `"REPO:<repo>"` during parsing. Add `getEndpointKeys(String repo)` to `GraphQueries` that filters by that tag. Pass `repo` from `checkSpecVsImpl`.

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- Modify: `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- Test: `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`

- [x] **Step 1: Write the failing test**

Add to `GraphQueriesTest.java`:

```java
@Test
void getEndpointKeys_scopedToRepo_excludesOtherRepos() {
    // Endpoint tagged with REPO:alpha
    store.applyEvent(new GraphEvent.NodeEvent("GET /api/alpha", "Endpoint",
        Set.of("ENDPOINT", "REPO:alpha"), "GET /api/alpha", null));
    store.applyEvent(new GraphEvent.NodeEvent("com.acme.AlphaController#list()", "Method",
        Set.of(), "public List<?> list()", null));
    store.applyEvent(new GraphEvent.EdgeEvent(
        "com.acme.AlphaController#list()", "GET /api/alpha", GraphEdge.EdgeType.EXPOSES));

    // Endpoint tagged with REPO:beta
    store.applyEvent(new GraphEvent.NodeEvent("POST /api/beta", "Endpoint",
        Set.of("ENDPOINT", "REPO:beta"), "POST /api/beta", null));
    store.applyEvent(new GraphEvent.NodeEvent("com.acme.BetaController#create()", "Method",
        Set.of(), "public void create()", null));
    store.applyEvent(new GraphEvent.EdgeEvent(
        "com.acme.BetaController#create()", "POST /api/beta", GraphEdge.EdgeType.EXPOSES));

    Set<String> alphaKeys = queries.getEndpointKeys("alpha");
    assertThat(alphaKeys).containsExactly("GET /api/alpha");
    assertThat(alphaKeys).doesNotContain("POST /api/beta");

    Set<String> allKeys = queries.getEndpointKeys(null);
    assertThat(allKeys).containsExactlyInAnyOrder("GET /api/alpha", "POST /api/beta");
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -pl . -Dtest=GraphQueriesTest#getEndpointKeys_scopedToRepo_excludesOtherRepos -q
```

Expected: FAIL — `getEndpointKeys` doesn't accept a String parameter yet.

- [x] **Step 3: Add `getEndpointKeys(String repo)` to GraphQueries**

Replace the existing `getEndpointKeys()` method in `GraphQueries.java` (lines 143–151) with:

```java
public Set<String> getEndpointKeys(String repo) {
    Set<String> repoNodes = repo != null ? store.getNodesByTag("REPO:" + repo) : null;
    Set<String> result = new HashSet<>();
    for (String nodeId : store.getNodesByTag("ENDPOINT")) {
        if (repoNodes != null && !repoNodes.contains(nodeId)) continue;
        boolean implemented = store.getInEdges(nodeId).stream()
            .anyMatch(edge -> edge.type() == GraphEdge.EdgeType.EXPOSES);
        if (implemented) result.add(nodeId);
    }
    return result;
}
```

- [x] **Step 4: Update JavaSourceParser to tag endpoint nodes with the repo**

In `JavaSourceParser.java`, find the line inside the method annotation loop (currently around line 72):

```java
events.add(new GraphEvent.NodeEvent(endpoint, "Endpoint", Set.of("ENDPOINT"), endpoint, fqn));
```

Replace it with:

```java
events.add(new GraphEvent.NodeEvent(endpoint, "Endpoint", Set.of("ENDPOINT", "REPO:" + repo), endpoint, fqn));
```

(The `repo` variable is the parameter of the enclosing `parse(String repo, ...)` method.)

- [x] **Step 5: Update RetrievalOrchestrator to pass repo**

In `RetrievalOrchestrator.java`, find `checkSpecVsImpl` (line 97):

```java
Set<String> implKeys = graphQueries.getEndpointKeys();
```

Change to:

```java
Set<String> implKeys = graphQueries.getEndpointKeys(repo);
```

- [x] **Step 6: Update existing getEndpointKeys test to use new signature**

In `GraphQueriesTest.java`, find `getEndpointKeys_returnsOnlyExposesReachableEndpoints`. The existing nodes don't have `REPO:` tags. The existing call:

```java
Set<String> implKeys = queries.getEndpointKeys();
```

Change to:

```java
Set<String> implKeys = queries.getEndpointKeys(null);
```

(Passing `null` preserves the "all repos" behavior for the untagged test nodes.)

- [x] **Step 7: Run all graph and parser tests**

```bash
mvn test -pl . -Dtest="GraphQueriesTest,JavaSourceParserTest" -q
```

Expected: all PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java \
        src/main/java/com/acme/airetrieval/graph/GraphQueries.java \
        src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java \
        src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java
git commit -m "fix: scope getEndpointKeys to repo to prevent multi-repo undocumented false positives"
```

---

## Task 3: Fix getBeanGraph depth > 1 traversal

**Bug:** `JavaSourceParser` emits `DEPENDS_ON` edges with `edge.to` = simple type name (e.g., `"PaymentService"`), but graph nodes are keyed by FQN (e.g., `"com.acme.PaymentService"`). BFS in `getBeanGraph` queues the simple name; subsequent `getEdgesByType("PaymentService", DEPENDS_ON, true)` finds no edges because that vertex doesn't exist. Depth > 1 always returns empty.

**Fix:** In `GraphQueries.getBeanGraph()`, when the `edge.to()` node is not found by direct lookup, resolve it against the set of BEAN-tagged nodes using the same fuzzy match the root lookup uses.

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- Test: `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`

- [x] **Step 1: Write the failing test**

Add to `GraphQueriesTest.java`:

```java
@Test
void getBeanGraph_depth2_traversesTransitiveDeps() {
    // Nodes stored by FQN
    store.applyEvent(new GraphEvent.NodeEvent("com.acme.OrderService", "Class",
        Set.of("BEAN"), "class OrderService", null));
    store.applyEvent(new GraphEvent.NodeEvent("com.acme.PaymentService", "Class",
        Set.of("BEAN"), "class PaymentService", null));
    store.applyEvent(new GraphEvent.NodeEvent("com.acme.RepoClient", "Class",
        Set.of("BEAN"), "class RepoClient", null));

    // Edges stored with simple type names (as JavaSourceParser emits them)
    store.applyEvent(new GraphEvent.EdgeEvent(
        "com.acme.OrderService", "PaymentService", GraphEdge.EdgeType.DEPENDS_ON));
    store.applyEvent(new GraphEvent.EdgeEvent(
        "com.acme.PaymentService", "RepoClient", GraphEdge.EdgeType.DEPENDS_ON));

    var result = queries.getBeanGraph("OrderService", 2);
    assertThat(result.root()).isEqualTo("com.acme.OrderService");
    assertThat(result.dependencies()).hasSize(2);

    var fqns = result.dependencies().stream().map(d -> d.beanFqn()).toList();
    assertThat(fqns).contains("com.acme.PaymentService", "com.acme.RepoClient");

    var depths = result.dependencies().stream().map(d -> d.depth()).toList();
    assertThat(depths).contains(1, 2);
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -pl . -Dtest=GraphQueriesTest#getBeanGraph_depth2_traversesTransitiveDeps -q
```

Expected: FAIL — `result.dependencies()` has size 1 (only `PaymentService` at depth 1, `RepoClient` never found).

- [x] **Step 3: Fix the BFS loop in GraphQueries.getBeanGraph()**

In `GraphQueries.java`, replace the `while (!queue.isEmpty())` block inside `getBeanGraph` with:

```java
while (!queue.isEmpty()) {
    String current = queue.poll();
    int currentDepth = depths.get(current);
    if (currentDepth >= depth) continue;

    for (GraphEdge edge : store.getEdgesByType(current, GraphEdge.EdgeType.DEPENDS_ON, true)) {
        String depId = edge.to();
        // JavaSourceParser stores simple type names as edge targets; resolve to FQN if needed
        if (store.getNode(depId).isEmpty()) {
            depId = store.getNodesByTag("BEAN").stream()
                .filter(id -> id.equals(edge.to()) || id.endsWith("." + edge.to()))
                .findFirst()
                .orElse(edge.to());
        }
        if (visited.add(depId)) {
            int nextDepth = currentDepth + 1;
            String signature = store.getNode(depId).map(GraphNode::signature).orElse(depId);
            dependencies.add(new BeanDep(depId, signature, nextDepth));
            queue.add(depId);
            depths.put(depId, nextDepth);
        }
    }
}
```

- [x] **Step 4: Run all graph tests**

```bash
mvn test -pl . -Dtest=GraphQueriesTest -q
```

Expected: all PASS including the existing `getBeanGraph_knownBean_returnsDependencies` and new `getBeanGraph_depth2_traversesTransitiveDeps`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/GraphQueries.java \
        src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java
git commit -m "fix: resolve simple-name DEPENDS_ON targets to FQN in getBeanGraph BFS"
```

---

## Task 4: Remove hardcoded domain=CODE from searchByNameFragment

**Bug:** `NrtLuceneSearcher.searchByNameFragment()` hardcodes `domain="CODE"` in its filter regardless of the caller's `type` parameter. FQN fragments for `OPENAPI_OP`, `KNOWLEDGE`, or `ENDPOINT` chunks are silently excluded from `find_symbol` results.

**Fix:** Remove the hardcoded domain. Let the `type` parameter (or absence thereof) be the only filter.

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- Test: `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

- [x] **Step 1: Write the failing test**

Add to `NrtLuceneSearcherTest.java`:

```java
@Test
void searchByNameFragment_includesNonCodeChunks() throws Exception {
    var dir = Files.createTempDirectory("nrt-domain");
    try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
        // KNOWLEDGE domain chunk with a FQN-like id
        indexer.upsert(new Chunk("op1", "repo", null, "spec.yaml", Domain.KNOWLEDGE, "OPENAPI_OP",
            "POST /api/orders", null, null, List.of(), "sha", "h1", "openapi",
            "POST /api/orders create order", null));
        indexer.commit();
        searcher.maybeReopen();

        var results = searcher.searchByNameFragment("orders", null, 10);
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(hit -> "POST /api/orders".equals(hit.fqn()));
    } finally {
        cleanup(dir);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -pl . -Dtest=NrtLuceneSearcherTest#searchByNameFragment_includesNonCodeChunks -q
```

Expected: FAIL — result is empty because domain filter excludes KNOWLEDGE chunk.

- [x] **Step 3: Fix searchByNameFragment in NrtLuceneSearcher**

In `NrtLuceneSearcher.java`, replace the `searchByNameFragment` method (lines 93–102):

```java
public List<SearchHit> searchByNameFragment(String partial, String type, int k) throws IOException {
    IndexSearcher searcher = manager.acquire();
    try {
        Query wildcard = new WildcardQuery(new Term("fqn", "*" + partial + "*"));
        SearchFilter filter = new SearchFilter(null, null, type, null, null);
        return toHits(searcher, searcher.search(applyFilter(wildcard, filter), k).scoreDocs);
    } finally {
        manager.release(searcher);
    }
}
```

(Only change: `"CODE"` → `null` in the `SearchFilter` constructor.)

- [x] **Step 4: Run the full Lucene searcher test suite**

```bash
mvn test -pl . -Dtest=NrtLuceneSearcherTest -q
```

Expected: all PASS — existing tests use CODE chunks and still find them; new test finds KNOWLEDGE chunk.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java \
        src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
git commit -m "fix: remove hardcoded domain=CODE filter from searchByNameFragment"
```

---

## Task 5: Replace leading wildcard with fqn_simple prefix search

**Bug:** `searchByNameFragment` uses `WildcardQuery(fqn, "*partial*")`. Lucene cannot use the inverted index trie for leading wildcards — it scans every term in the `fqn` field linearly. On a large index this degrades to O(N) per `find_symbol` call.

**Fix:** Index the simple name (last segment of the FQN, lowercased) in a separate `fqn_simple` field. In `searchByNameFragment`, combine a fast trailing-wildcard on `fqn_simple` with an exact TermQuery on `fqn`, replacing the leading wildcard. Covers the common case: searching "Payment" finds "com.acme.PaymentService".

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java`
- Modify: `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- Test: `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

- [x] **Step 1: Write the failing test**

The old `WildcardQuery` on the `fqn` `StringField` is case-sensitive. Searching with a lowercased partial (e.g., `"paymentservice"`) produces `*paymentservice*` which does NOT match the stored value `"com.acme.PaymentService"` (mixed case). After the fix, `fqn_simple` stores `"paymentservice"` (lowercase), so `"paymentservice*"` matches.

Add to `NrtLuceneSearcherTest.java`:

```java
@Test
void searchByNameFragment_lowercasePrefixMatchViaFqnSimple() throws Exception {
    var dir = Files.createTempDirectory("nrt-simple");
    try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
        indexer.upsert(new Chunk("c1", "repo", null, "PaymentService.java", Domain.CODE, "CLASS",
            "com.acme.PaymentService", null, null, List.of(), "sha", "h1", "java",
            "class PaymentService", null));
        indexer.upsert(new Chunk("m1", "repo", null, "OrderService.java", Domain.CODE, "CLASS",
            "com.acme.OrderService", null, null, List.of(), "sha", "h2", "java",
            "class OrderService", null));
        indexer.commit();
        searcher.maybeReopen();

        // Lowercase "paymentservice" matches fqn_simple (stored lowercase) but NOT the StringField fqn
        // (which stores "com.acme.PaymentService" — WildcardQuery is case-sensitive on StringField).
        var results = searcher.searchByNameFragment("paymentservice", null, 10);
        assertThat(results).isNotEmpty();
        assertThat(results).anyMatch(hit -> "com.acme.PaymentService".equals(hit.fqn()));
        assertThat(results).noneMatch(hit -> "com.acme.OrderService".equals(hit.fqn()));
    } finally {
        cleanup(dir);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -pl . -Dtest=NrtLuceneSearcherTest#searchByNameFragment_lowercasePrefixMatchViaFqnSimple -q
```

Expected: FAIL — `*paymentservice*` on `StringField("fqn", "com.acme.PaymentService")` is case-sensitive, no match → empty result.

- [x] **Step 3: Add fqn_simple field in LuceneIndexer.upsert()**

In `LuceneIndexer.java`, add this block inside `upsert()` after the existing `add(doc, "fqn", chunk.fqn())` line (around line 41):

```java
if (chunk.fqn() != null) {
    String fqn = chunk.fqn();
    int sep = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('#'));
    String simple = sep >= 0 ? fqn.substring(sep + 1) : fqn;
    int paren = simple.indexOf('(');
    if (paren >= 0) simple = simple.substring(0, paren);
    doc.add(new StringField("fqn_simple", simple.toLowerCase(), Field.Store.NO));
}
```

No new imports needed — `StringField` and `Field` are already imported.

- [x] **Step 4: Replace the query in NrtLuceneSearcher.searchByNameFragment()**

In `NrtLuceneSearcher.java`, replace the `searchByNameFragment` method body (as modified in Task 4) with:

```java
public List<SearchHit> searchByNameFragment(String partial, String type, int k) throws IOException {
    IndexSearcher searcher = manager.acquire();
    try {
        String lower = partial.toLowerCase();
        BooleanQuery mainQuery = new BooleanQuery.Builder()
            .add(new WildcardQuery(new Term("fqn_simple", lower + "*")), BooleanClause.Occur.SHOULD)
            .add(new TermQuery(new Term("fqn", partial)), BooleanClause.Occur.SHOULD)
            .build();
        SearchFilter filter = new SearchFilter(null, null, type, null, null);
        return toHits(searcher, searcher.search(applyFilter(mainQuery, filter), k).scoreDocs);
    } finally {
        manager.release(searcher);
    }
}
```

`BooleanQuery` is already imported. `TermQuery` is already imported.

- [x] **Step 5: Run the full Lucene searcher test suite**

```bash
mvn test -pl . -Dtest=NrtLuceneSearcherTest -q
```

Expected: all PASS including `searchByNameFragment_lowercasePrefixMatchViaFqnSimple`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/LuceneIndexer.java \
        src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java \
        src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
git commit -m "fix: index fqn_simple field and replace leading wildcard with prefix search in searchByNameFragment"
```

---

## Task 6: Add SymbolListResult and fix find_symbol error signaling

**Bug:** `McpTools.find_symbol()` catches `IOException` and returns `List.of()`, which is identical to "no matching symbols found." MCP clients have no way to distinguish a successful empty search from an index failure.

**Fix:** Create a `SymbolListResult` record (analogous to `SearchResult`) with `symbols` and `error` fields. Change `find_symbol` to return `SymbolListResult`.

**Files:**
- Create: `src/main/java/com/acme/airetrieval/retrieve/dto/SymbolListResult.java`
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

- [x] **Step 1: Write the failing tests**

Add to `McpToolsTest.java`:

```java
@Test
void find_symbol_returnsSymbolListResultWithSymbols() throws Exception {
    when(retrieval.findSymbols("PaymentService", null, 10))
        .thenReturn(List.of(new SymbolRef("com.acme.PaymentService", "class PaymentService", "CLASS", "PaymentService.java")));
    var result = tools.find_symbol("PaymentService", null);
    assertThat(result.symbols()).hasSize(1);
    assertThat(result.error()).isNull();
    assertThat(result.isError()).isFalse();
}

@Test
void find_symbol_returnsErrorResultOnException() throws Exception {
    when(retrieval.findSymbols(anyString(), any(), anyInt()))
        .thenThrow(new java.io.IOException("index locked"));
    var result = tools.find_symbol("Foo", null);
    assertThat(result.isError()).isTrue();
    assertThat(result.error()).contains("index locked");
    assertThat(result.symbols()).isEmpty();
}
```

Also update the existing `find_symbol_delegatesToRetrieval` test in `McpToolsTest.java` — it currently asserts `result.size()` on a `List<SymbolRef>`. Change it to:

```java
@Test
void find_symbol_delegatesToRetrieval() throws Exception {
    when(retrieval.findSymbols("PaymentService", null, 10))
        .thenReturn(List.of(new SymbolRef("com.acme.PaymentService", "class PaymentService", "CLASS", "PaymentService.java")));
    var result = tools.find_symbol("PaymentService", null);
    assertThat(result.symbols()).hasSize(1);
    assertThat(result.symbols().get(0).fqn()).isEqualTo("com.acme.PaymentService");
}
```

Add this import to `McpToolsTest.java`:

```java
import com.acme.airetrieval.retrieve.dto.SymbolListResult;
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: FAIL — `SymbolListResult` doesn't exist yet.

- [x] **Step 3: Create SymbolListResult.java**

Create `src/main/java/com/acme/airetrieval/retrieve/dto/SymbolListResult.java`:

```java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SymbolListResult(List<SymbolRef> symbols, String error) {
    public static SymbolListResult ok(List<SymbolRef> symbols) {
        return new SymbolListResult(symbols, null);
    }
    public static SymbolListResult err(String message) {
        return new SymbolListResult(List.of(), message);
    }
    public boolean isError() {
        return error != null;
    }
}
```

- [x] **Step 4: Update find_symbol in McpTools.java**

In `McpTools.java`, replace the `find_symbol` method and update its import:

Add this import with the other DTO imports:

```java
import com.acme.airetrieval.retrieve.dto.SymbolListResult;
```

Replace the `find_symbol` method (lines 107–115):

```java
@Tool(description = "Discover symbols by partial class name, method name, or FQN fragment")
public SymbolListResult find_symbol(
    @ToolParam(description = "partial class name, method name, or FQN fragment") String partialName,
    @ToolParam(description = "optional type filter: CLASS, METHOD, INTERFACE, ENDPOINT, or null for all") String type) {
    try {
        return SymbolListResult.ok(retrieval.findSymbols(partialName, type, 10));
    } catch (Exception e) {
        return SymbolListResult.err(e.getMessage());
    }
}
```

- [x] **Step 5: Run McpTools tests**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/dto/SymbolListResult.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "fix: add SymbolListResult and propagate errors from find_symbol instead of swallowing"
```

---

## Task 7: Add error field to SpecImplReport and fix check_spec_vs_impl

**Bug:** `McpTools.check_spec_vs_impl()` catches exceptions and returns `new SpecImplReport(repo, List.of(), List.of(), List.of())`, which is indistinguishable from a successful run that found no spec or impl endpoints. MCP clients see a silent empty result on Lucene I/O failures.

**Fix:** Add a nullable `error` field to `SpecImplReport`. Propagate the exception message on failure.

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java`
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

- [x] **Step 1: Write the failing test**

Add to `McpToolsTest.java`:

```java
@Test
void check_spec_vs_impl_returnsErrorOnException() throws Exception {
    when(retrieval.checkSpecVsImpl("myrepo"))
        .thenThrow(new java.io.IOException("searcher closed"));
    var result = tools.check_spec_vs_impl("myrepo");
    assertThat(result.repo()).isEqualTo("myrepo");
    assertThat(result.error()).isNotNull();
    assertThat(result.error()).contains("searcher closed");
    assertThat(result.unimplemented()).isEmpty();
    assertThat(result.undocumented()).isEmpty();
    assertThat(result.matched()).isEmpty();
}
```

Also update the existing `check_spec_vs_impl_delegatesToRetrieval` test to add `null` as the fifth constructor argument (after the record is updated):

```java
@Test
void check_spec_vs_impl_delegatesToRetrieval() throws Exception {
    var report = new SpecImplReport("myrepo", List.of(), List.of(), List.of(), null);
    when(retrieval.checkSpecVsImpl("myrepo")).thenReturn(report);
    var result = tools.check_spec_vs_impl("myrepo");
    assertThat(result.repo()).isEqualTo("myrepo");
    assertThat(result.error()).isNull();
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: FAIL — `SpecImplReport` has no `error()` accessor yet.

- [x] **Step 3: Add error field to SpecImplReport**

Replace the full content of `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java`:

```java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SpecImplReport(
    String repo,
    List<String> unimplemented,
    List<String> undocumented,
    List<String> matched,
    String error
) {}
```

- [x] **Step 4: Fix the two call sites that construct SpecImplReport**

**In `RetrievalOrchestrator.java`** (line 102), change:

```java
return new SpecImplReport(repo, unimplemented, undocumented, matched);
```

to:

```java
return new SpecImplReport(repo, unimplemented, undocumented, matched, null);
```

**In `McpTools.java`** (line 197), change:

```java
return new SpecImplReport(repo, List.of(), List.of(), List.of());
```

to:

```java
return new SpecImplReport(repo, List.of(), List.of(), List.of(), e.getMessage());
```

- [x] **Step 5: Run McpTools and registration tests**

```bash
mvn test -pl . -Dtest="McpToolsTest,McpToolsRegistrationTest" -q
```

Expected: all PASS.

- [x] **Step 6: Run full test suite**

```bash
mvn test -q
```

Expected: BUILD SUCCESS, all tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "fix: add error field to SpecImplReport and surface exception in check_spec_vs_impl"
```

---

## Verification

After all 7 tasks:

```bash
mvn test -q
```

Expected: BUILD SUCCESS.

Smoke-test server startup:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--kira.index-dir=/tmp/kira-smoke-bugfix
curl -s http://localhost:8080/actuator/health
```

Expected: `{"status":"UP"}`.
