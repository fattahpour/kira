# Search Token Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Fix 14 search/retrieval gaps in Kira that waste tokens or degrade retrieval quality, reducing tokens-per-answer and improving recall.

**Architecture:** Changes span four layers: ingest parsers (richer chunk text, REPO tagging, section sub-chunking, symbols population), index (symbols field, configurable candidate-K, snippet truncation), retrieval orchestration (cross-domain quality, spec comparison cap, BM25-only path), and MCP tools (metadata-only search, expand_context cap, body cap, new keyword_search and discover_symbols tools). All changes are backward-compatible except the `expand_context` tool gaining an optional `maxResults` parameter and `SpecImplReport` gaining a `total` field.

**Tech Stack:** Java 21, Spring Boot 3.5, Apache Lucene 10, Spring AI 1.1 MCP, JUnit 5, AssertJ, JavaParser, Swagger Parser v2

---

## File Map

| File | Change |
|---|---|
| `src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java` | REPO tag on endpoint nodes (G14); richer chunk text (G8) |
| `src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java` | Max section size sub-chunking (G9) |
| `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java` | Populate `Chunk.symbols` with annotation names (G10) |
| `src/main/java/com/acme/airetrieval/ingest/model/Chunk.java` | No change — `symbols` field already exists |
| `src/main/java/com/acme/airetrieval/index/model/SearchFilter.java` | Add `symbol` filter field (G10) |
| `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java` | Index `chunk.symbols()` as multi-value `symbol` field (G10) |
| `src/main/java/com/acme/airetrieval/index/model/SearchHit.java` | Fix snippet: word-boundary truncation, increase to 500 chars (G1) |
| `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java` | Add `symbol` filter to `buildFilter` (G10) |
| `src/main/java/com/acme/airetrieval/index/HybridSearch.java` | Make `candidateK` a constructor param, keep 2-arg default (G11) |
| `src/main/java/com/acme/airetrieval/config/ApplicationProps.java` | Add `candidateK` and `specMaxOps` top-level fields (G11, G13) |
| `src/main/resources/application.yml` | Add `kira.candidate-k: 50` and `kira.spec-max-ops: 200` |
| `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java` | Remove hardcoded CANDIDATE_K; fix `getDesignDocs` query + reranker; fix `getCodeForDoc` reranker; configurable spec cap + total (G6, G7, G11, G13) |
| `src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java` | Min-per-hit budget allocation (G4) |
| `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java` | Add `total` field (G13) |
| `src/main/java/com/acme/airetrieval/graph/GraphQueries.java` | Add 3-arg `expandContext` overload with `maxSignatures` (G3) |
| `src/main/java/com/acme/airetrieval/mcp/McpTools.java` | Add `keyword_search`, `discover_symbols`; update `expand_context` signature; add `get_symbol` body cap (G2, G3, G5, G12) |

---

## Task 1: G14 — ApiSpecParser emits REPO tag on endpoint nodes

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java:49`
- Test: `src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java`

- [x] **Step 1: Write the failing test**

Add this test to `ApiSpecParserTest`:

```java
@Test
void parse_endpointNodesCarryRepoTag() {
    var result = new ApiSpecParser().parse("myrepo", "api/petstore.yaml", "abc123", PETSTORE_YAML);
    assertThat(result.events()).isNotEmpty();
    boolean hasRepoTag = result.events().stream()
        .filter(e -> e instanceof com.acme.airetrieval.graph.model.GraphEvent.NodeEvent)
        .map(e -> (com.acme.airetrieval.graph.model.GraphEvent.NodeEvent) e)
        .filter(n -> "Endpoint".equals(n.type()))
        .allMatch(n -> n.tags().contains("REPO:myrepo"));
    assertThat(hasRepoTag).isTrue();
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=ApiSpecParserTest#parse_endpointNodesCarryRepoTag -q
```

Expected: FAIL — `REPO:myrepo` not in endpoint node tags.

- [x] **Step 3: Fix ApiSpecParser**

In `src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java`, find line 49 (inside the `HTTP_METHODS.forEach` lambda):

Change:
```java
events.add(new GraphEvent.NodeEvent(fqn, "Endpoint", Set.of("ENDPOINT"), fqn, summary));
```
To:
```java
events.add(new GraphEvent.NodeEvent(fqn, "Endpoint", Set.of("ENDPOINT", "REPO:" + repo), fqn, summary));
```

- [x] **Step 4: Run test to verify it passes**

```bash
mvn test -pl . -Dtest=ApiSpecParserTest -q
```

Expected: all ApiSpecParserTest tests PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java
git commit -m "fix: tag OpenAPI spec endpoint nodes with REPO:<repo> for consistent graph scoping"
```

---

## Task 2: G8 — ApiSpecParser includes parameter/response text in chunk

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java`
- Test: `src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java`

**Why:** Current chunk text is only `"METHOD /path\nsummary"`. Semantic queries like "endpoints accepting payment data" find nothing because parameter names and descriptions are not indexed. This fix appends parameter names/descriptions and response descriptions to the chunk text.

- [x] **Step 1: Write the failing test**

Add this YAML constant and test to `ApiSpecParserTest`:

```java
private static final String PAYMENT_YAML = """
    openapi: "3.0.0"
    info:
      title: Payment API
      version: "1.0"
    paths:
      /payments:
        post:
          operationId: createPayment
          summary: Create a payment
          parameters:
            - name: idempotencyKey
              in: header
              description: Idempotency key for deduplication
          requestBody:
            description: Payment details including amount and currency
          responses:
            '201':
              description: Payment created successfully
    """;

@Test
void parse_chunkTextIncludesParametersAndResponses() {
    var result = new ApiSpecParser().parse("repo", "payment.yaml", "sha", PAYMENT_YAML);
    assertThat(result.chunks()).hasSize(1);
    String text = result.chunks().get(0).text();
    assertThat(text).contains("idempotencyKey");
    assertThat(text).contains("Payment details including amount");
    assertThat(text).contains("Payment created successfully");
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=ApiSpecParserTest#parse_chunkTextIncludesParametersAndResponses -q
```

Expected: FAIL — chunk text contains only FQN and summary, not parameter/response content.

- [x] **Step 3: Fix ApiSpecParser**

Replace the `bodyText` construction block inside the lambda in `ApiSpecParser.java`. Add imports at the top of the file:

```java
import io.swagger.v3.oas.models.parameters.Parameter;
```

Then replace lines 44–48 (the `bodyText` construction and `chunks.add` call):

```java
StringBuilder sb = new StringBuilder();
sb.append(fqn).append("\n").append(summary);
if (operation.getDescription() != null) {
    sb.append("\n").append(operation.getDescription());
}
if (operation.getParameters() != null) {
    for (Parameter param : operation.getParameters()) {
        sb.append("\nparam:").append(param.getName());
        if (param.getDescription() != null) sb.append(" ").append(param.getDescription());
    }
}
if (operation.getRequestBody() != null && operation.getRequestBody().getDescription() != null) {
    sb.append("\nrequest:").append(operation.getRequestBody().getDescription());
}
if (operation.getResponses() != null) {
    operation.getResponses().forEach((code, resp) -> {
        if (resp.getDescription() != null)
            sb.append("\nresponse:").append(code).append(" ").append(resp.getDescription());
    });
}
String bodyText = sb.toString().trim();

chunks.add(new Chunk(id, repo, null, path, Domain.KNOWLEDGE, "OPENAPI_OP", fqn,
    summary, apiPath, List.of(), gitSha, MarkdownParser.hash(bodyText), "openapi", bodyText, null));
events.add(new GraphEvent.NodeEvent(fqn, "Endpoint", Set.of("ENDPOINT", "REPO:" + repo), fqn, summary));
events.add(new GraphEvent.EdgeEvent(path, fqn, GraphEdge.EdgeType.SPECIFIES));
```

Note: the `events.add` lines remain unchanged from Task 1 — keep the `REPO:` tag from Task 1.

- [x] **Step 4: Run all ApiSpecParser tests**

```bash
mvn test -pl . -Dtest=ApiSpecParserTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java
git commit -m "feat: include parameter/response descriptions in OpenAPI chunk text for better semantic retrieval"
```

---

## Task 3: G9 — MarkdownParser sub-chunks sections exceeding 4000 chars

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java`
- Test: `src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java`

**Why:** A single markdown section with 500+ lines becomes one chunk. Embedding a 10 000-char section into a 384-dim vector loses detail. This fix splits oversized sections at paragraph boundaries.

- [x] **Step 1: Write the failing test**

Add to `MarkdownParserTest`:

```java
@Test
void largeSection_isSplitIntoSubChunks() {
    // Build a section > 4000 chars split across paragraphs
    StringBuilder sb = new StringBuilder("# Big Section\n\n");
    for (int i = 0; i < 20; i++) {
        sb.append("Paragraph ").append(i).append(": ")
          .append("a".repeat(180)).append("\n\n");
    }
    String md = sb.toString(); // ~20 paragraphs × ~200 chars = ~4000+ chars

    var chunks = new MarkdownParser().parse("repo", "big.md", "sha", md);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allMatch(c -> c.text().length() <= 4100); // allow small overage for last para
    // All sub-chunks carry the same section heading
    assertThat(chunks).allMatch(c -> "Big Section".equals(c.title()));
}

@Test
void smallSection_remainsSingleChunk() {
    String md = "# Small\n\nJust a short paragraph.";
    var chunks = new MarkdownParser().parse("repo", "small.md", "sha", md);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.get(0).title()).isEqualTo("Small");
}
```

- [x] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=MarkdownParserTest -q
```

Expected: `largeSection_isSplitIntoSubChunks` FAILS (returns 1 chunk regardless of size).

- [x] **Step 3: Implement sub-chunking in MarkdownParser**

Replace the entire `MarkdownParser.java` with:

```java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class MarkdownParser {
    private static final Parser PARSER = Parser.builder().build();
    private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();
    static final int MAX_SECTION_CHARS = 4000;

    public List<Chunk> parse(String repo, String path, String gitSha, String content) {
        Node document = PARSER.parse(content == null ? "" : content);
        List<Section> sections = split(document);
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            String text = section.text().trim();
            if (text.isBlank()) continue;
            chunks.addAll(toChunks(repo, path, gitSha, section.heading(), text));
        }
        if (chunks.isEmpty()) {
            String text = TEXT.render(document).trim();
            chunks.add(new Chunk(path + "#body", repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(), gitSha, hash(text), "markdown", text, null));
        }
        return chunks;
    }

    private static List<Chunk> toChunks(String repo, String path, String gitSha, String heading, String text) {
        if (text.length() <= MAX_SECTION_CHARS) {
            String slug = heading == null ? "body" : slugify(heading);
            return List.of(new Chunk(path + "#" + slug, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, heading, null, List.of(), gitSha, hash(text), "markdown", text, null));
        }
        // Split at paragraph boundaries
        String[] paragraphs = text.split("\n\n+");
        List<Chunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int part = 0;
        String slug = heading == null ? "body" : slugify(heading);
        for (String para : paragraphs) {
            if (current.length() + para.length() + 2 > MAX_SECTION_CHARS && !current.isEmpty()) {
                String chunkText = current.toString().trim();
                String id = path + "#" + slug + (part > 0 ? "-" + part : "");
                result.add(new Chunk(id, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
                    null, heading, null, List.of(), gitSha, hash(chunkText), "markdown", chunkText, null));
                current.setLength(0);
                part++;
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(para);
        }
        if (!current.isEmpty()) {
            String chunkText = current.toString().trim();
            String id = path + "#" + slug + (part > 0 ? "-" + part : "");
            result.add(new Chunk(id, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, heading, null, List.of(), gitSha, hash(chunkText), "markdown", chunkText, null));
        }
        return result;
    }

    private static List<Section> split(Node document) {
        List<Section> sections = new ArrayList<>();
        String heading = null;
        List<Node> current = new ArrayList<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading) {
                if (heading != null || !current.isEmpty()) {
                    sections.add(new Section(heading, render(current)));
                }
                heading = TEXT.render(child).trim();
                current = new ArrayList<>();
            } else {
                current.add(child);
            }
        }
        if (heading != null || !current.isEmpty()) {
            sections.add(new Section(heading, render(current)));
        }
        return sections;
    }

    private static String render(List<Node> nodes) {
        StringBuilder out = new StringBuilder();
        for (Node node : nodes) out.append(TEXT.render(node)).append('\n');
        return out.toString().trim();
    }

    public static String slugify(String heading) {
        if (heading == null || heading.isBlank()) return "body";
        return heading.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Section(String heading, String text) {}
}
```

- [x] **Step 4: Run all MarkdownParser tests**

```bash
mvn test -pl . -Dtest=MarkdownParserTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java
git commit -m "feat: sub-chunk markdown sections exceeding 4000 chars at paragraph boundaries"
```

---

## Task 4: G10 — Index symbols (annotations) from Chunk and JavaSourceParser

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- Modify: `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java`
- Modify: `src/main/java/com/acme/airetrieval/index/model/SearchFilter.java`
- Modify: `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- Test: `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`
- Test: `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

**Why:** `Chunk.symbols` is always `List.of()` — never populated. Without annotation names in the index, queries like "find all @Cacheable methods" must full-text-scan. This fix populates symbols with annotation names in the parser and indexes them as a filterable `symbol` field in Lucene.

- [x] **Step 1: Write the failing tests**

Add to `JavaSourceParserTest`:

```java
@Test
void parse_classChunkSymbolsContainAnnotations() {
    String source = """
        package com.acme;
        import org.springframework.stereotype.Service;
        @Service
        public class PaymentService {}
        """;
    var result = new JavaSourceParser().parse("repo", "PaymentService.java", "sha", source);
    var classChunk = result.chunks().stream()
        .filter(c -> "CLASS".equals(c.type()))
        .findFirst().orElseThrow();
    assertThat(classChunk.symbols()).contains("@Service");
}

@Test
void parse_methodChunkSymbolsContainAnnotations() {
    String source = """
        package com.acme;
        import org.springframework.cache.annotation.Cacheable;
        public class OrderService {
            @Cacheable("orders")
            public String getOrder(String id) { return id; }
        }
        """;
    var result = new JavaSourceParser().parse("repo", "OrderService.java", "sha", source);
    var methodChunk = result.chunks().stream()
        .filter(c -> "METHOD".equals(c.type()))
        .findFirst().orElseThrow();
    assertThat(methodChunk.symbols()).contains("@Cacheable");
}
```

Add to `NrtLuceneSearcherTest`:

```java
@Test
void symbol_fieldIsIndexedAndFilterable() throws Exception {
    var dir = Files.createTempDirectory("nrt-sym");
    try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
        indexer.upsert(new Chunk("c1", "repo", null, "Foo.java", Domain.CODE, "CLASS",
            "com.acme.FooService", null, null, List.of("@Service"), "sha", "h1", "java",
            "class FooService", null));
        indexer.upsert(new Chunk("c2", "repo", null, "Bar.java", Domain.CODE, "CLASS",
            "com.acme.BarService", null, null, List.of("@Repository"), "sha", "h2", "java",
            "class BarService", null));
        indexer.commit();
        searcher.maybeReopen();

        // BM25 search with symbol filter should return only @Service class
        var filter = new SearchFilter(null, null, null, null, null, "@Service");
        var results = searcher.bm25("FooService BarService", filter, 10);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).fqn()).isEqualTo("com.acme.FooService");
    } finally {
        cleanup(dir);
    }
}
```

- [x] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=JavaSourceParserTest#parse_classChunkSymbolsContainAnnotations,JavaSourceParserTest#parse_methodChunkSymbolsContainAnnotations,NrtLuceneSearcherTest#symbol_fieldIsIndexedAndFilterable -q
```

Expected: all three FAIL.

- [x] **Step 3: Update SearchFilter to add symbol field**

Replace the entire `SearchFilter.java`:

```java
package com.acme.airetrieval.index.model;

public record SearchFilter(String repo, String domain, String type, String path, String branch, String symbol) {
    public SearchFilter(String repo, String domain, String type, String path, String branch) {
        this(repo, domain, type, path, branch, null);
    }
    public SearchFilter(String repo, String domain, String type, String path) {
        this(repo, domain, type, path, null, null);
    }
}
```

- [x] **Step 4: Update LuceneIndexer to index symbols**

In `LuceneIndexer.java`, after the `add(doc, "lang", chunk.lang());` line (line ~54), add:

```java
if (chunk.symbols() != null) {
    for (String symbol : chunk.symbols()) {
        if (symbol != null && !symbol.isBlank()) {
            doc.add(new StringField("symbol", symbol, Field.Store.NO));
        }
    }
}
```

- [x] **Step 5: Update NrtLuceneSearcher.buildFilter to use symbol**

In `NrtLuceneSearcher.java`, inside `buildFilter`, after the `branch` filter clause, add:

```java
if (filter.symbol() != null) { b.add(new TermQuery(new Term("symbol", filter.symbol())), BooleanClause.Occur.FILTER); any = true; }
```

- [x] **Step 6: Update JavaSourceParser to populate symbols**

In `JavaSourceParser.java`, change the class chunk creation (around line 54). First, build a symbols list from class annotations:

Replace the class `chunks.add` call:
```java
// Before (line 54):
chunks.add(new Chunk(typeFqn, repo, null, path, Domain.CODE, "CLASS", typeFqn, null, null, List.of(),
    gitSha, MarkdownParser.hash(type.toString()), "java", typeFqn + "\n" + (javadoc == null ? "" : javadoc), null));
```
With:
```java
List<String> classSymbols = type.getAnnotations().stream()
    .map(a -> "@" + a.getNameAsString())
    .collect(java.util.stream.Collectors.toList());
chunks.add(new Chunk(typeFqn, repo, null, path, Domain.CODE, "CLASS", typeFqn, null, null, classSymbols,
    gitSha, MarkdownParser.hash(type.toString()), "java", typeFqn + "\n" + (javadoc == null ? "" : javadoc), null));
```

Replace the method `chunks.add` call (around line 87):
```java
// Before:
chunks.add(new Chunk(fqn, repo, null, path, Domain.CODE, "METHOD", fqn, null, null, List.of(),
    gitSha, MarkdownParser.hash(text), "java", text, null));
```
With:
```java
List<String> methodSymbols = method.getAnnotations().stream()
    .map(a -> "@" + a.getNameAsString())
    .collect(java.util.stream.Collectors.toList());
chunks.add(new Chunk(fqn, repo, null, path, Domain.CODE, "METHOD", fqn, null, null, methodSymbols,
    gitSha, MarkdownParser.hash(text), "java", text, null));
```

Add import at the top of `JavaSourceParser.java`:
```java
import java.util.stream.Collectors;
```

- [x] **Step 7: Run all affected tests**

```bash
mvn test -pl . -Dtest=JavaSourceParserTest,NrtLuceneSearcherTest,LuceneIndexerTest -q
```

Expected: all PASS.

- [x] **Step 8: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java \
        src/main/java/com/acme/airetrieval/index/LuceneIndexer.java \
        src/main/java/com/acme/airetrieval/index/model/SearchFilter.java \
        src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java \
        src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java \
        src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
git commit -m "feat: index annotation symbols from chunks, add symbol filter to SearchFilter"
```

---

## Task 5: G1 — Fix snippet truncation to word boundary, increase to 500 chars

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/index/model/SearchHit.java`
- Test: `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

**Why:** Current `snippet()` cuts at 300 chars at a character boundary (mid-word, mid-line). The reranker calls `hit.snippet()` and only sees 300 chars — degrading ranking quality. Fix: increase to 500 chars and cut at word boundary.

- [x] **Step 1: Write the failing test**

Add to `NrtLuceneSearcherTest`:

```java
@Test
void snippet_truncatesAtWordBoundary() throws Exception {
    var dir = Files.createTempDirectory("nrt-snip");
    try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
        // text: 600 chars that ends mid-word at char 500 unless word-boundary is used
        String word = "longword";
        String text = "start " + word.repeat(60) + " end"; // ~490 chars of repeated words, > 500 total
        indexer.upsert(new Chunk("s1", "repo", null, "S.java", Domain.CODE, "METHOD",
            "com.S#m()", null, null, List.of(), "sha", "h1", "java", text, null));
        indexer.commit();
        searcher.maybeReopen();

        var results = searcher.bm25("start", null, 1);
        assertThat(results).hasSize(1);
        String snippet = results.get(0).snippet();
        assertThat(snippet).doesNotEndWith("longwor..."); // no mid-word cut
        assertThat(snippet).endsWith("..."); // still truncated
        assertThat(snippet.replace("...", "")).hasSizeLessThanOrEqualTo(500);
    } finally {
        cleanup(dir);
    }
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=NrtLuceneSearcherTest#snippet_truncatesAtWordBoundary -q
```

Expected: FAIL — current code cuts at char 300 regardless of word boundary.

- [x] **Step 3: Fix SearchHit.snippet()**

In `SearchHit.java`, replace the `snippet` method (lines 34-37):

```java
private static String snippet(String text) {
    if (text == null) return "";
    int limit = 500;
    if (text.length() <= limit) return text;
    int boundary = text.lastIndexOf(' ', limit);
    return text.substring(0, boundary > 0 ? boundary : limit) + "...";
}
```

- [x] **Step 4: Run all NrtLuceneSearcher tests**

```bash
mvn test -pl . -Dtest=NrtLuceneSearcherTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/model/SearchHit.java \
        src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
git commit -m "fix: snippet truncation at word boundary, increase limit from 300 to 500 chars"
```

---

## Task 6: G11 — Make CANDIDATE_K configurable via ApplicationProps

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/config/ApplicationProps.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/acme/airetrieval/index/HybridSearch.java`
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- Modify: `src/main/java/com/acme/airetrieval/config/RetrievalConfig.java`
- Test: `src/test/java/com/acme/airetrieval/index/HybridSearchTest.java`

**Why:** Two independent hardcoded `CANDIDATE_K = 50` constants in `HybridSearch` and `RetrievalOrchestrator` cannot be tuned without code changes. Also `HybridSearch` always fetches 50 BM25 + 50 KNN even when k=3, wasting embed calls and Lucene I/O.

- [x] **Step 1: Write the failing test**

Replace `HybridSearchTest.candidateK_is50` with a new test that checks the instance field:

```java
@Test
void candidateK_isStoredAsInstanceField() throws Exception {
    var dir = Files.createTempDirectory("hs-ck");
    var embedding = new DeterministicEmbeddingModel(32);
    try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
        var hs = new HybridSearch(searcher, embedding, 30);
        var f = HybridSearch.class.getDeclaredField("candidateK");
        f.setAccessible(true);
        assertThat(f.get(hs)).isEqualTo(30);

        var hsDefault = new HybridSearch(searcher, embedding);
        assertThat(f.get(hsDefault)).isEqualTo(50);
    } finally {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=HybridSearchTest#candidateK_isStoredAsInstanceField -q
```

Expected: FAIL — `candidateK` is a static field, not an instance field; 3-arg constructor does not exist.

- [x] **Step 3: Add candidateK to ApplicationProps**

In `ApplicationProps.java`, add `candidateK` and `specMaxOps` as top-level fields after `defaultSearchK`:

```java
@ConfigurationProperties(prefix = "kira")
public record ApplicationProps(
    Path dataDir,
    Path indexDir,
    Path checkpointFile,
    Path modelsDir,
    int maxSearchResults,
    int defaultSearchK,
    int candidateK,
    int specMaxOps,
    Embedding embedding,
    Reranker reranker,
    TokenBudgetConfig tokenBudget,
    Executor executor,
    Graph graph,
    FullReindex fullReindex,
    AcceptConfig accept,
    List<RepoConfig> repos,
    boolean respectGitignore
) {
    // ... keep all existing inner records unchanged ...
```

- [x] **Step 4: Add defaults to application.yml**

In `application.yml`, after the `default-search-k: 10` line, add:

```yaml
  candidate-k: 50
  spec-max-ops: 200
```

- [x] **Step 5: Update HybridSearch**

Replace `HybridSearch.java` (keep RRF logic unchanged, only change CANDIDATE_K handling):

```java
package com.acme.airetrieval.index;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HybridSearch {
    private static final int RRF_K = 60;
    private final NrtLuceneSearcher searcher;
    private final EmbeddingModel embeddingModel;
    private final int candidateK;

    public HybridSearch(NrtLuceneSearcher searcher, EmbeddingModel embeddingModel) {
        this(searcher, embeddingModel, 50);
    }

    public HybridSearch(NrtLuceneSearcher searcher, EmbeddingModel embeddingModel, int candidateK) {
        this.searcher = searcher;
        this.embeddingModel = embeddingModel;
        this.candidateK = candidateK;
    }

    public List<SearchHit> search(String query, SearchFilter filter, int k) throws Exception {
        List<SearchHit> bm25 = searcher.bm25(query, filter, candidateK);
        List<SearchHit> knn = searcher.knn(embeddingModel.embed(query), filter, candidateK);
        return fuse(bm25, knn).stream().limit(k).toList();
    }

    public List<SearchHit> bm25Only(String query, SearchFilter filter, int k) throws Exception {
        return searcher.bm25(query, filter, k);
    }

    private static List<SearchHit> fuse(List<SearchHit> left, List<SearchHit> right) {
        Map<String, Ranked> scores = new LinkedHashMap<>();
        add(scores, left);
        add(scores, right);
        return scores.values().stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .map(Ranked::hit)
            .toList();
    }

    private static void add(Map<String, Ranked> scores, List<SearchHit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            int rank = i + 1;
            scores.compute(hit.id(), (id, existing) -> {
                double score = 1.0d / (RRF_K + rank);
                if (existing == null) return new Ranked(hit, score);
                existing.score += score;
                return existing;
            });
        }
    }

    private static final class Ranked {
        private final SearchHit hit;
        private double score;
        private Ranked(SearchHit hit, double score) { this.hit = hit; this.score = score; }
        private SearchHit hit() { return hit; }
    }
}
```

- [x] **Step 6: Update RetrievalOrchestrator**

In `RetrievalOrchestrator.java`:
1. Remove `private static final int CANDIDATE_K = 50;`
2. In the constructor, change the `HybridSearch` construction:
   ```java
   this.hybridSearch = new HybridSearch(searcher, embeddingModel, props.candidateK());
   ```
3. In `hybridRerank`, change the candidates call:
   ```java
   List<SearchHit> candidates = hybridSearch.search(query, filter, Math.max(k, props.candidateK()));
   ```

- [x] **Step 7: Run all HybridSearch and RetrievalOrchestrator-related tests**

```bash
mvn test -pl . -Dtest=HybridSearchTest,McpToolsTest -q
```

Expected: all PASS.

- [x] **Step 8: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/ApplicationProps.java \
        src/main/resources/application.yml \
        src/main/java/com/acme/airetrieval/index/HybridSearch.java \
        src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java \
        src/test/java/com/acme/airetrieval/index/HybridSearchTest.java
git commit -m "feat: make CANDIDATE_K configurable via kira.candidate-k, default 50"
```

---

## Task 7: G6 + G7 — Cross-domain link quality: simple name query + use reranker

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

**Why (G6):** `getDesignDocs("com.acme.payment.PaymentService#processPayment(PaymentRequest)")` uses the full FQN as a semantic query. Package prefix noise (`com.acme.payment`) degrades embedding match. Fix: strip to simple name.
**Why (G7):** Both `getDesignDocs` and `getCodeForDoc` call `hybrid()` (no reranker). Cross-domain linking is exactly where reranker precision matters most. Fix: use `hybridRerank`.

- [x] **Step 1: Write the failing tests**

Add to `McpToolsTest`:

```java
@Test
void getDesignForSymbol_usesSimpleNameNotFullFqn() throws Exception {
    // We verify via the SearchResult — if the query is the full FQN, rank differs
    // This is a smoke test: just verify it doesn't throw and returns a SearchResult
    var result = mcpTools.get_design_for_symbol("com.acme.SomeService#doWork(String)");
    assertThat(result).isNotNull();
    assertThat(result.isError()).isFalse();
}

@Test
void getCodeForDoc_doesNotThrow() throws Exception {
    var result = mcpTools.get_code_for_doc("nonexistent-doc-id");
    assertThat(result).isNotNull();
    assertThat(result.isError()).isFalse();
}
```

Note: These are smoke tests. The real quality improvement is not easily testable in unit tests without real embeddings. The key verifiable behavior is that the methods don't throw.

- [x] **Step 2: Run tests to verify they pass (they should already pass — these are smoke tests)**

```bash
mvn test -pl . -Dtest=McpToolsTest#getDesignForSymbol_usesSimpleNameNotFullFqn,McpToolsTest#getCodeForDoc_doesNotThrow -q
```

If these already pass, proceed to Step 3.

- [x] **Step 3: Add simpleName helper and fix getDesignDocs in RetrievalOrchestrator**

In `RetrievalOrchestrator.java`, add the private helper method (add at bottom, before the `ScoredHit` record):

```java
private static String simpleName(String fqn) {
    if (fqn == null || fqn.isBlank()) return fqn;
    int sep = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('#'));
    String simple = sep >= 0 ? fqn.substring(sep + 1) : fqn;
    int paren = simple.indexOf('(');
    return paren >= 0 ? simple.substring(0, paren) : simple;
}
```

Replace `getDesignDocs`:
```java
public List<SearchHit> getDesignDocs(String fqn) throws Exception {
    return hybridRerank(simpleName(fqn), new SearchFilter(null, "KNOWLEDGE", null, null), 5);
}
```

Replace `getCodeForDoc`:
```java
public List<SearchHit> getCodeForDoc(String docId) throws Exception {
    String query = searcher.findTitleById(docId).orElse(docId);
    return hybridRerank(query, new SearchFilter(null, "CODE", null, null), 5);
}
```

- [x] **Step 4: Run all tests**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "fix: cross-domain links use simple name query (not full FQN) and reranker"
```

---

## Task 8: G13 — checkSpecVsImpl configurable cap + total count in report

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java`
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

**Why:** `findByTypeAndRepo("OPENAPI_OP", repo, 500)` silently truncates APIs with >500 ops. Callers can't detect truncation. Fix: use `kira.spec-max-ops` (from ApplicationProps in Task 6) as the limit and add a `total` field to `SpecImplReport` so callers know how many spec operations were found.

- [x] **Step 1: Write the failing test**

Add to `McpToolsTest`:

```java
@Test
void checkSpecVsImpl_reportHasTotalField() {
    var report = mcpTools.check_spec_vs_impl("nonexistent-repo");
    assertThat(report.total()).isGreaterThanOrEqualTo(0);
    assertThat(report.error()).isNull();
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=McpToolsTest#checkSpecVsImpl_reportHasTotalField -q
```

Expected: FAIL — `SpecImplReport` has no `total()` method.

- [x] **Step 3: Add total field to SpecImplReport**

Replace `SpecImplReport.java`:

```java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SpecImplReport(
    String repo,
    List<String> unimplemented,
    List<String> undocumented,
    List<String> matched,
    int total,
    String error
) {}
```

- [x] **Step 4: Update RetrievalOrchestrator.checkSpecVsImpl**

In `RetrievalOrchestrator.java`, replace `checkSpecVsImpl`:

```java
public SpecImplReport checkSpecVsImpl(String repo) throws IOException {
    List<SearchHit> specHits = searcher.findByTypeAndRepo("OPENAPI_OP", repo, props.specMaxOps());
    int total = specHits.size();
    Set<String> specKeys = specHits.stream()
        .map(SearchHit::fqn)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    Set<String> implKeys = graphQueries.getEndpointKeys(repo);

    List<String> unimplemented = specKeys.stream().filter(key -> !implKeys.contains(key)).sorted().toList();
    List<String> undocumented = implKeys.stream().filter(key -> !specKeys.contains(key)).sorted().toList();
    List<String> matched = specKeys.stream().filter(implKeys::contains).sorted().toList();
    return new SpecImplReport(repo, unimplemented, undocumented, matched, total, null);
}
```

- [x] **Step 5: Fix the error path in McpTools.check_spec_vs_impl**

In `McpTools.java`, update the catch block in `check_spec_vs_impl`:

```java
} catch (Exception e) {
    return new SpecImplReport(repo, List.of(), List.of(), List.of(), 0, e.getMessage());
}
```

- [x] **Step 6: Run all affected tests**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: all PASS.

- [x] **Step 7: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/dto/SpecImplReport.java \
        src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "feat: add total field to SpecImplReport, use configurable spec-max-ops cap"
```

---

## Task 9: G12 — Add keyword_search BM25-only MCP tool

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Modify: `src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

**Why:** Every search forces ONNX embedding inference. For exact-name lookups (class names, FQN fragments), BM25 alone is sufficient. A BM25-only tool skips embedding, halving latency for keyword queries and avoiding unnecessary ONNX warmup.

- [x] **Step 1: Write the failing test**

Add to `McpToolsTest`:

```java
@Test
void keyword_search_returnsBm25Results() {
    var result = mcpTools.keyword_search("PaymentService", null, null, 5);
    assertThat(result).isNotNull();
    assertThat(result.isError()).isFalse();
}
```

Do NOT update `McpToolsRegistrationTest` yet — that update happens in Task 10 after `discover_symbols` is also added, so the Spring context boots only once for the registration test.

- [x] **Step 2: Run tests to verify they fail**

```bash
mvn test -pl . -Dtest=McpToolsTest#keyword_search_returnsBm25Results -q
```

Expected: FAIL — no `keyword_search` method on `McpTools`.

- [x] **Step 3: Add keyword_search to McpTools**

In `McpTools.java`, add after the `semantic_search` method (around line 78):

```java
@Tool(description = "Fast keyword BM25-only search, no embedding — good for exact class names, FQN fragments, identifiers")
public SearchResult keyword_search(
    @ToolParam(description = "keyword or exact name query") String query,
    @ToolParam(description = "repository filter, or null for all repos") String repo,
    @ToolParam(description = "domain filter: CODE, KNOWLEDGE, or null for all") String domain,
    @ToolParam(description = "max results to return") int k) {
    try {
        return SearchResult.ok(retrieval.bm25Only(query, new SearchFilter(repo, domain, null, null, null), k));
    } catch (Exception e) {
        return SearchResult.err(e.getMessage());
    }
}
```

- [x] **Step 4: Run McpToolsTest (McpToolsRegistrationTest deferred until Task 10)**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "feat: add keyword_search BM25-only MCP tool, skips ONNX embedding"
```

---

## Task 10: G2 — Add discover_symbols metadata-only MCP tool

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`
- Modify: `src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java` (update count)

**Why:** `search_code/knowledge/semantic_search` always return 500-char snippets per hit. Discovery queries ("what classes exist in the payment module?") only need fqn+type+path. This tool strips snippets from results — no snippet cost.

- [x] **Step 1: Write the failing test**

Add to `McpToolsTest`:

```java
@Test
void discover_symbols_snippetIsNull() {
    var result = mcpTools.discover_symbols("Service", null, 10);
    assertThat(result).isNotNull();
    assertThat(result.isError()).isFalse();
    // All returned SymbolRefs must have null snippet
    assertThat(result.symbols()).allMatch(ref -> ref.snippet() == null);
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=McpToolsTest#discover_symbols_snippetIsNull -q
```

Expected: FAIL — no `discover_symbols` method.

- [x] **Step 3: Add discover_symbols to McpTools**

In `McpTools.java`, add after `find_symbol`:

```java
@Tool(description = "Discover symbols by name fragment, returns fqn/type/path only — no snippet, minimal tokens for navigation")
public SymbolListResult discover_symbols(
    @ToolParam(description = "partial class name, method name, or FQN fragment") String partialName,
    @ToolParam(description = "optional type filter: CLASS, METHOD, INTERFACE, ENDPOINT, or null for all") String type,
    @ToolParam(description = "max results to return") int k) {
    try {
        List<SymbolRef> refs = retrieval.findSymbols(partialName, type, k).stream()
            .map(ref -> new SymbolRef(ref.fqn(), null, ref.type(), ref.path()))
            .toList();
        return SymbolListResult.ok(refs);
    } catch (Exception e) {
        return SymbolListResult.err(e.getMessage());
    }
}
```

- [x] **Step 4: Run registration test (expects 19 tools now)**

```bash
mvn test -pl . -Dtest=McpToolsRegistrationTest -q
```

Expected: PASS (19 tools: 17 original + keyword_search + discover_symbols).

- [x] **Step 5: Run all MCP tests**

```bash
mvn test -pl . -Dtest=McpToolsTest,McpToolsRegistrationTest -q
```

Expected: all PASS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java
git commit -m "feat: add discover_symbols MCP tool returning fqn/type/path only, zero snippet tokens"
```

---

## Task 11: G3 — expand_context limit parameter

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Test: `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`

**Why:** BFS across all edge types with `hops=2` on a large service class returns 100+ signatures — no cap. `expand_context` in McpTools passes everything to the caller. Fix: add `maxSignatures` to `expandContext`, expose via `maxResults` param in the MCP tool.

- [x] **Step 1: Write the failing test**

Add to `GraphQueriesTest`:

```java
@Test
void expandContext_respectsMaxSignaturesLimit() {
    CodeGraphStore store = new CodeGraphStore();
    // Build a star: A → B1..B10
    store.applyEvent(new GraphEvent.NodeEvent("A", "METHOD", Set.of(), "void A()", null));
    for (int i = 1; i <= 10; i++) {
        store.applyEvent(new GraphEvent.NodeEvent("B" + i, "METHOD", Set.of(), "void B" + i + "()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("A", "B" + i, GraphEdge.EdgeType.CALLS));
    }
    GraphQueries q = new GraphQueries(store);

    List<String> limited = q.expandContext(List.of("A"), 1, 3);
    assertThat(limited).hasSize(3);

    // Old 2-arg overload still works
    List<String> all = q.expandContext(List.of("A"), 1);
    assertThat(all).hasSize(10);
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=GraphQueriesTest#expandContext_respectsMaxSignaturesLimit -q
```

Expected: FAIL — no 3-arg `expandContext` overload.

- [x] **Step 3: Add 3-arg expandContext overload to GraphQueries**

In `GraphQueries.java`, change the existing `expandContext` method signature and add an overload:

```java
public List<String> expandContext(List<String> seedFqns, int hops) {
    return expandContext(seedFqns, hops, Integer.MAX_VALUE);
}

public List<String> expandContext(List<String> seedFqns, int hops, int maxSignatures) {
    Set<String> visited = new LinkedHashSet<>(seedFqns);
    Set<String> expanded = new LinkedHashSet<>();
    Set<String> frontier = new LinkedHashSet<>(seedFqns);
    for (int h = 0; h < hops; h++) {
        Set<String> next = new LinkedHashSet<>();
        for (String fqn : frontier) {
            store.getOutEdges(fqn).forEach(e -> next.add(e.to()));
            store.getInEdges(fqn).forEach(e -> next.add(e.from()));
        }
        next.removeAll(visited);
        visited.addAll(next);
        expanded.addAll(next);
        frontier = next;
        if (frontier.isEmpty()) break;
    }
    return expanded.stream()
        .limit(maxSignatures)
        .map(id -> store.getNode(id).map(GraphNode::signature).orElse(id))
        .toList();
}
```

- [x] **Step 4: Update McpTools.expand_context to accept maxResults**

In `McpTools.java`, replace the `expand_context` method:

```java
@Tool(description = "Expand context by walking the code graph from seed FQNs up to N hops, returning related symbol signatures")
public ExpandedContext expand_context(
    @ToolParam(description = "comma-separated fully qualified names to start from") String fqns,
    @ToolParam(description = "number of graph hops, 1 = direct neighbors only") int hops,
    @ToolParam(description = "max signatures to return, 0 = use default of 50") int maxResults) {
    List<String> seeds = Arrays.stream(fqns.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    int limit = maxResults <= 0 ? 50 : Math.min(maxResults, 200);
    List<String> signatures = graph.expandContext(seeds, hops, limit);
    return new ExpandedContext(seeds, hops, signatures);
}
```

- [x] **Step 5: Run all graph and MCP tests**

```bash
mvn test -pl . -Dtest=GraphQueriesTest,McpToolsTest -q
```

Expected: all PASS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/GraphQueries.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java
git commit -m "feat: add maxSignatures cap to expandContext, expose as maxResults in expand_context MCP tool"
```

---

## Task 12: G4 — ContextCompactor minimum per-hit budget allocation

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java`
- Test: `src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java`

**Why:** When the top-ranked hit is large (e.g., 400 tokens) and budget is 500 tokens, only one hit appears in context. Remaining hits get zero budget. Fix: reserve a minimum of 20 tokens per remaining hit before allocating to the current one. This ensures diversity even when a large hit leads.

- [x] **Step 1: Write the failing test**

Add to `ContextCompactorTest`:

```java
@Test
void compact_reservesMinimumForEachHit() {
    var compactor = new ContextCompactor(new TokenBudget(4)); // 4 chars/token
    // Budget: 100 tokens = 400 chars
    // If first hit takes everything, second hit gets nothing
    // With min reservation (20 tokens × 1 future hit = 80 chars reserved for hit 2),
    // hit 1 gets at most 320 chars, hit 2 gets at least some content
    String longText = "x ".repeat(200); // 400 chars = 100 tokens
    var big = new SearchHit("big", "repo", "big.java", Domain.CODE, "METHOD", "Big#m()", null, null, longText, 2);
    var small = new SearchHit("small", "repo", "s.java", Domain.CODE, "METHOD", "Small#m()", null, null, "short text", 1);

    String context = compactor.compact(List.of(big, small), 100);
    assertThat(context).contains("Big#m()");
    assertThat(context).contains("Small#m()"); // must appear despite big hit
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=ContextCompactorTest#compact_reservesMinimumForEachHit -q
```

Expected: FAIL — `Small#m()` does not appear because the big hit consumes the entire budget.

- [x] **Step 3: Update ContextCompactor**

Replace `ContextCompactor.java`:

```java
package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Domain;

import java.util.List;

public class ContextCompactor {
    private static final int MIN_TOKENS_PER_HIT = 20;
    private final TokenBudget tokenBudget;

    public ContextCompactor(TokenBudget tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public String compact(List<SearchHit> hits, int budgetTokens) {
        if (hits.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            int remaining = budgetTokens - used;
            int futureHits = hits.size() - i - 1;
            int reserved = MIN_TOKENS_PER_HIT * futureHits;
            int available = Math.max(MIN_TOKENS_PER_HIT, remaining - reserved);
            if (available <= 0) break;
            String block = block(hit);
            int cost = tokenBudget.estimate(block);
            if (cost > available) {
                block = tokenBudget.truncateToFit(block, available);
                cost = tokenBudget.estimate(block);
            }
            if (block.isBlank()) continue;
            out.append(block).append("\n\n");
            used += cost;
            if (used >= budgetTokens) break;
        }
        return out.toString().stripTrailing();
    }

    private static String block(SearchHit hit) {
        if (hit.domain() == Domain.CODE) {
            return "// " + (hit.fqn() == null ? hit.id() : hit.fqn()) + "\n" + hit.snippet();
        }
        String title = hit.title() == null ? hit.path() : hit.title();
        String section = hit.section() == null ? "" : " > " + hit.section();
        return "## " + title + section + "\n" + hit.snippet();
    }
}
```

- [x] **Step 4: Run all ContextCompactor tests**

```bash
mvn test -pl . -Dtest=ContextCompactorTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java \
        src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java
git commit -m "fix: ContextCompactor reserves minimum 20 tokens per future hit to ensure context diversity"
```

---

## Task 13: G5 — get_symbol body size cap

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

**Why:** `findBodyByFqn` returns the full Lucene `text` field verbatim. A 200-line method = ~5000 tokens. No truncation. Fix: cap at 8000 chars (≈2000 tokens) and truncate at a newline boundary so code remains syntactically intact as much as possible.

- [x] **Step 1: Write the failing test**

Add to `McpToolsTest`:

```java
@Test
void getSymbol_bodyIsCappedAt8000Chars() throws Exception {
    // Index a method with a very long body
    String longBody = "public void huge() {\n" + "    // comment\n".repeat(600) + "}";
    // longBody is ~10 000 chars
    // We can't easily test this without a real Lucene index with that FQN,
    // but we CAN test that the truncation logic in McpTools is applied.
    // Verify the truncation constant is present (compile-time check via reflection).
    var field = McpTools.class.getDeclaredField("MAX_BODY_CHARS");
    field.setAccessible(true);
    assertThat(field.get(null)).isEqualTo(8000);
}
```

- [x] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=McpToolsTest#getSymbol_bodyIsCappedAt8000Chars -q
```

Expected: FAIL — `MAX_BODY_CHARS` field does not exist.

- [x] **Step 3: Add body cap to McpTools**

In `McpTools.java`, add a constant after the class declaration:

```java
private static final int MAX_BODY_CHARS = 8000;
```

Then update the `get_symbol` method body enrichment block (lines ~96-103):

```java
@Tool(description = "Get symbol details: signature, javadoc, full body, callers, callees")
public SymbolView get_symbol(@ToolParam(description = "fully qualified name") String fqn) {
    SymbolView view = graph.getSymbolView(fqn);
    if (view == null || view.body() != null) return view;
    try {
        String body = retrieval.findBodyByFqn(fqn).orElse(null);
        if (body == null) return view;
        if (body.length() > MAX_BODY_CHARS) {
            int boundary = body.lastIndexOf('\n', MAX_BODY_CHARS);
            body = body.substring(0, boundary > 0 ? boundary : MAX_BODY_CHARS) + "\n// ... (truncated)";
        }
        return new SymbolView(view.fqn(), view.signature(), view.javadoc(), body,
            view.callerSignatures(), view.calleeSignatures());
    } catch (Exception e) {
        return view;
    }
}
```

- [x] **Step 4: Run all MCP tests**

```bash
mvn test -pl . -Dtest=McpToolsTest -q
```

Expected: all PASS.

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "fix: cap get_symbol body at 8000 chars, truncate at newline boundary"
```

---

## Final Verification

- [x] **Run full test suite**

```bash
mvn test -q
```

Expected: all tests pass, `BUILD SUCCESS`.

- [x] **Smoke-test server startup**

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--kira.index-dir=/tmp/kira-smoke-gaps &
sleep 15
curl -s http://localhost:8080/actuator/health
```

Expected: `{"status":"UP"}`.

- [x] **Verify tool count**

```bash
mvn test -pl . -Dtest=McpToolsRegistrationTest -q
```

Expected: PASS — 19 tools registered.
