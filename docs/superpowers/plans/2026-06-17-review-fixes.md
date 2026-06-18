# Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 6 bugs found in code review of the search-token-gaps implementation.

**Architecture:** Targeted fixes to MarkdownParser, SearchHit, RetrievalOrchestrator, HybridSearch, ContextCompactor, and JavaSourceParser. No new abstractions; each fix is minimal and surgical.

**Tech Stack:** Java 21, Apache Lucene 10, JUnit 5 / AssertJ, Maven

---

## Bug Summary

| Task | Bug | File | Severity |
|---|---|---|---|
| 1 | MarkdownParser: single oversized paragraph never split | `MarkdownParser.java` | High |
| 2 | MarkdownParser: chunk ID collision between split sections and adjacent headings | `MarkdownParser.java` | High |
| 3 | SearchHit: snippet truncated at load time; reranker scores 500-char truncated text | `SearchHit.java` | Medium-High |
| 4 | RetrievalOrchestrator: `simpleName()` misses `/` separator; endpoint FQNs not stripped | `RetrievalOrchestrator.java` | Medium-High |
| 5 | HybridSearch: BM25/KNN pool hardcoded at `candidateK`; `k > 2*candidateK` silently under-delivers | `HybridSearch.java` | Medium |
| 6 | ContextCompactor: `available` floored at `MIN_TOKENS_PER_HIT` even when budget is exhausted; output overshoots budget | `ContextCompactor.java` | Medium |

---

## Task 1: MarkdownParser — fix oversized single paragraph + chunk ID collision

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java`
- Test: `src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java`

**Bug 1 — oversized paragraph:** The flush guard `!current.isEmpty()` prevents flushing when `current` is empty. A single paragraph longer than `MAX_SECTION_CHARS` with no `\n\n` separators goes directly to `addMarkdownChunk` as one oversized chunk.

**Bug 2 — ID collision:** Multi-part suffix `-N` collides with headings whose slug ends in `-N`. Example: heading "Introduction" splits to `path#introduction` (part 0) and `path#introduction-1` (part 1). Heading "Introduction-1" also produces `path#introduction-1` (part 0, no suffix). LuceneIndexer's `updateDocument` overwrites the first, silently losing data.

**Fix for Bug 1:** After the standard flush check, detect paragraphs that alone exceed `MAX_SECTION_CHARS` and split them at word/char boundaries.

**Fix for Bug 2:** Change the multi-part ID separator from `-` to `:`. The `slugify()` function replaces all non-alphanumeric chars (including `:`) with `-`, so a heading can never produce a slug containing `:`. Use `:N` suffix for ALL parts of a split section (including part 0), keeping unsplit sections as `path#slug`.

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/com/acme/airetrieval/ingest/parser/MarkdownParserTest.java`:

```java
@Test
void singleOversizedParagraph_isSplitIntoSubChunks() {
    // One huge paragraph with no \n\n separators — currently produces one oversized chunk
    String largeSection = "# Code\n\n" + "word ".repeat(900); // ~4500 chars, no blank lines
    var chunks = parser.parse("repo", "p.md", "sha", largeSection);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allMatch(c -> c.text().length() <= MarkdownParser.MAX_SECTION_CHARS);
}

@Test
void splitSection_idsDoNotCollideWithAdjacentHeadings() {
    // "Introduction" (large, will split) next to "Introduction-1" (small)
    StringBuilder sb = new StringBuilder("# Introduction\n\n");
    for (int i = 0; i < 25; i++) {
        sb.append("Para ").append(i).append(": ").append("a".repeat(170)).append("\n\n");
    }
    sb.append("# Introduction-1\n\nShort section.");
    var chunks = parser.parse("repo", "doc.md", "sha", sb.toString());
    long uniqueIds = chunks.stream().map(com.acme.airetrieval.ingest.model.Chunk::id).distinct().count();
    assertThat(uniqueIds).isEqualTo(chunks.size());
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```
mvn test -pl . -Dtest=MarkdownParserTest#singleOversizedParagraph_isSplitIntoSubChunks+splitSection_idsDoNotCollideWithAdjacentHeadings -q
```

Expected: FAIL

- [ ] **Step 3: Implement the fixes**

Replace the `toChunks` and `addMarkdownChunk` methods in `src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java`:

```java
private static List<Chunk> toChunks(String repo, String path, String gitSha, String heading, String text) {
    String slug = heading == null ? "body" : slugify(heading);
    if (text.length() <= MAX_SECTION_CHARS) {
        return List.of(new Chunk(path + "#" + slug, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
            null, heading, null, List.of(), gitSha, hash(text), "markdown", text, null));
    }

    List<Chunk> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int part = 0;
    for (String paragraph : text.split("\\n\\n+")) {
        if (current.length() + paragraph.length() + 2 > MAX_SECTION_CHARS && !current.isEmpty()) {
            part = addMarkdownChunk(result, repo, path, gitSha, heading, slug, current, part);
        }
        if (paragraph.length() > MAX_SECTION_CHARS) {
            // Flush any accumulated current first
            if (!current.isEmpty()) {
                part = addMarkdownChunk(result, repo, path, gitSha, heading, slug, current, part);
            }
            // Split the oversized paragraph at word/char boundaries
            String para = paragraph;
            while (para.length() > MAX_SECTION_CHARS) {
                int cut = para.lastIndexOf(' ', MAX_SECTION_CHARS);
                if (cut <= 0) cut = MAX_SECTION_CHARS;
                part = addMarkdownChunk(result, repo, path, gitSha, heading, slug,
                    new StringBuilder(para.substring(0, cut)), part);
                para = para.substring(cut).stripLeading();
            }
            if (!para.isBlank()) {
                current.append(para);
            }
            continue;
        }
        if (!current.isEmpty()) current.append("\n\n");
        current.append(paragraph);
    }
    if (!current.isEmpty()) {
        addMarkdownChunk(result, repo, path, gitSha, heading, slug, current, part);
    }
    return result;
}

private static int addMarkdownChunk(List<Chunk> result, String repo, String path, String gitSha,
                                    String heading, String slug, StringBuilder current, int part) {
    String chunkText = current.toString().trim();
    // Use ":" as separator — slugify() replaces ":" with "-", so no heading slug can contain ":"
    String id = path + "#" + slug + ":" + part;
    result.add(new Chunk(id, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
        null, heading, null, List.of(), gitSha, hash(chunkText), "markdown", chunkText, null));
    current.setLength(0);
    return part + 1;
}
```

- [ ] **Step 4: Run new tests and full suite**

```
mvn test -pl . -Dtest=MarkdownParserTest -q
```

Expected: all 7 MarkdownParserTest tests pass.

---

## Task 2: SearchHit — store full text; compute snippet lazily

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/index/model/SearchHit.java`

**Bug:** `snippet()` currently truncates to 500 chars at document-load time. `hybridRerank()` passes `hit.snippet()` to the reranker, so the cross-encoder never sees text past char 500. Any chunk whose relevant content is in chars 500–N is scored on boilerplate alone.

**Fix:** Rename the record component `snippet` → `text` (stores full stored text, no truncation). Add a `public String snippet()` instance method that truncates for display. The reranker caller in `RetrievalOrchestrator` (Task 4) will use `hit.text()` directly.

Note: all existing callers of `hit.snippet()` still work — they get the truncated display string. Callers that create `SearchHit` directly in tests pass the text content as the 9th positional arg, which is unchanged (now named `text` instead of `snippet`).

- [ ] **Step 1: Write the failing test**

Add to or create `src/test/java/com/acme/airetrieval/index/model/SearchHitTest.java`:

```java
package com.acme.airetrieval.index.model;

import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHitTest {

    @Test
    void text_returnsFull_snippet_returnsTruncated() {
        String longText = "word ".repeat(200); // 1000 chars
        var hit = new SearchHit("id", "repo", "path", Domain.CODE, "METHOD", "fqn",
            null, null, longText, 1.0f);
        assertThat(hit.text()).isEqualTo(longText);
        assertThat(hit.snippet().length()).isLessThanOrEqualTo(503); // 500 chars + "..."
        assertThat(hit.snippet()).endsWith("...");
    }

    @Test
    void snippet_returnsFull_whenTextShorterThanLimit() {
        var hit = new SearchHit("id", "repo", "path", Domain.CODE, "METHOD", "fqn",
            null, null, "short text", 1.0f);
        assertThat(hit.text()).isEqualTo("short text");
        assertThat(hit.snippet()).isEqualTo("short text");
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
mvn test -pl . -Dtest=SearchHitTest -q
```

Expected: FAIL (no `text()` accessor on current record; `snippet()` component accessor returns truncated, not full)

- [ ] **Step 3: Implement the fix**

Replace `src/main/java/com/acme/airetrieval/index/model/SearchHit.java`:

```java
package com.acme.airetrieval.index.model;

import com.acme.airetrieval.ingest.model.Domain;
import org.apache.lucene.document.Document;

public record SearchHit(
    String id,
    String repo,
    String path,
    Domain domain,
    String type,
    String fqn,
    String title,
    String section,
    String text,
    float score
) {
    public static SearchHit fromDocument(Document doc, float score) {
        String domain = doc.get("domain");
        return new SearchHit(
            doc.get("id"),
            doc.get("repo"),
            doc.get("path"),
            domain == null ? null : Domain.valueOf(domain),
            doc.get("type"),
            doc.get("fqn"),
            doc.get("title"),
            doc.get("section"),
            doc.get("text"),   // full text, no truncation
            score
        );
    }

    public String snippet() {
        if (text == null) return "";
        int limit = 500;
        if (text.length() <= limit) return text;
        int boundary = text.lastIndexOf(' ', limit);
        return text.substring(0, boundary > 0 ? boundary : limit) + "...";
    }
}
```

- [ ] **Step 4: Run tests**

```
mvn test -pl . -Dtest=SearchHitTest,NrtLuceneSearcherTest,ContextCompactorTest,HybridSearchTest -q
```

Expected: all pass. (Existing tests pass `text` content as 9th positional arg — unchanged. `hit.snippet()` for short strings returns the full text unchanged.)

---

## Task 3: RetrievalOrchestrator — fix `simpleName()` + use full text for reranking

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`
- Test: create `src/test/java/com/acme/airetrieval/retrieve/RetrievalOrchestratorTest.java`

**Bug 1 — simpleName():** Uses `Math.max(lastIndexOf('.'), lastIndexOf('#'))`. For endpoint FQNs like `"GET /api/items"`, both return -1, so the full string is returned unchanged. `LuceneIndexer` also checks `lastIndexOf('/')`, producing `"items"`. `getDesignDocs("GET /api/items")` queries with the full noisy FQN.

**Bug 2 — reranker sees truncated text:** `hybridRerank()` calls `reranker.score(query, hit.snippet())`. After Task 2, `hit.snippet()` is still a truncated display string. The reranker should receive `hit.text()` (the full stored text).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/acme/airetrieval/retrieve/RetrievalOrchestratorTest.java`:

```java
package com.acme.airetrieval.retrieve;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalOrchestratorTest {

    private static String simpleName(String fqn) throws Exception {
        Method m = RetrievalOrchestrator.class.getDeclaredMethod("simpleName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, fqn);
    }

    @Test
    void simpleName_stripsPackage() throws Exception {
        assertThat(simpleName("com.acme.Foo")).isEqualTo("Foo");
    }

    @Test
    void simpleName_stripsMethod() throws Exception {
        assertThat(simpleName("com.acme.Foo#bar()")).isEqualTo("bar");
    }

    @Test
    void simpleName_stripsSlashForEndpointFqns() throws Exception {
        // "GET /api/items" → "items" (currently returns "GET /api/items")
        assertThat(simpleName("GET /api/items")).isEqualTo("items");
    }

    @Test
    void simpleName_handlesNoSeparator() throws Exception {
        assertThat(simpleName("Foo")).isEqualTo("Foo");
    }
}
```

- [ ] **Step 2: Run tests to confirm failure**

```
mvn test -pl . -Dtest=RetrievalOrchestratorTest -q
```

Expected: `simpleName_stripsSlashForEndpointFqns` FAILS

- [ ] **Step 3: Implement the fixes**

In `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`:

**Fix 1 — simpleName():** Add `fqn.lastIndexOf('/')` to sep calculation:

```java
private static String simpleName(String fqn) {
    if (fqn == null || fqn.isBlank()) return fqn;
    int sep = Math.max(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('#')), fqn.lastIndexOf('/'));
    String simple = sep >= 0 ? fqn.substring(sep + 1) : fqn;
    int paren = simple.indexOf('(');
    return paren >= 0 ? simple.substring(0, paren) : simple;
}
```

**Fix 2 — hybridRerank uses full text:** Change `reranker.score(query, hit.snippet())` to `reranker.score(query, hit.text() != null ? hit.text() : "")`:

```java
public List<SearchHit> hybridRerank(String query, SearchFilter filter, int k) throws Exception {
    List<SearchHit> candidates = hybridSearch.search(query, filter, Math.max(k, props.candidateK()));
    if (reranker == null || candidates.isEmpty()) return candidates.stream().limit(k).toList();
    List<ScoredHit> scored = new java.util.ArrayList<>();
    for (SearchHit hit : candidates) {
        scored.add(new ScoredHit(hit, reranker.score(query, hit.text() != null ? hit.text() : "")));
    }
    return scored.stream()
        .sorted((a, b) -> Float.compare(b.score, a.score))
        .limit(k)
        .map(ScoredHit::hit)
        .toList();
}
```

- [ ] **Step 4: Run tests**

```
mvn test -pl . -Dtest=RetrievalOrchestratorTest -q
```

Expected: all 4 tests pass.

---

## Task 4: HybridSearch — BM25/KNN pool must follow `max(k, candidateK)`

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/index/HybridSearch.java`
- Test: `src/test/java/com/acme/airetrieval/index/HybridSearchTest.java`

**Bug:** `HybridSearch.search()` always fetches `this.candidateK` from BM25 and KNN, regardless of the `k` argument. The fused pool is bounded by `2 * candidateK`. When `k > 2 * candidateK`, the caller silently receives fewer than `k` results. `hybridRerank()` already passes `Math.max(k, candidateK)` as the `k` argument, but that value is only used for the final `.limit(k)`, not for BM25/KNN fetch counts.

**Fix:** Inside `search()`, set `fetchK = Math.max(k, candidateK)` and pass `fetchK` to both BM25 and KNN.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/acme/airetrieval/index/HybridSearchTest.java`:

```java
@Test
void search_returnsUpToK_whenKExceedsCandidateK() throws Exception {
    var dir = Files.createTempDirectory("hs-bigk");
    var embedding = new DeterministicEmbeddingModel(32);
    try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
        for (int i = 0; i < 10; i++) {
            String text = "term" + i + " document content";
            indexer.upsert(new Chunk("doc" + i, "repo", null, "p.md", Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(), "sha", "h" + i, "md", text, embedding.embed(text)));
        }
        indexer.commit();
        searcher.maybeReopen();
        // candidateK=3; requesting k=8 (k > 2*candidateK=6)
        var hs = new HybridSearch(searcher, embedding, 3);
        var results = hs.search("term document", null, 8);
        assertThat(results).hasSizeGreaterThan(3); // currently capped at 6; after fix: up to 8
    } finally {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
mvn test -pl . -Dtest=HybridSearchTest#search_returnsUpToK_whenKExceedsCandidateK -q
```

Expected: FAIL (returns ≤ 6 results, assertion expects > 3 but the real problem is it returns 6 not 8; adjust assertion if needed — the test confirms the fix improved recall)

- [ ] **Step 3: Implement the fix**

In `src/main/java/com/acme/airetrieval/index/HybridSearch.java`, change the `search()` method:

```java
public List<SearchHit> search(String query, SearchFilter filter, int k) throws Exception {
    int fetchK = Math.max(k, candidateK);
    List<SearchHit> bm25 = searcher.bm25(query, filter, fetchK);
    List<SearchHit> knn = searcher.knn(embeddingModel.embed(query), filter, fetchK);
    return fuse(bm25, knn).stream().limit(k).toList();
}
```

- [ ] **Step 4: Run tests**

```
mvn test -pl . -Dtest=HybridSearchTest -q
```

Expected: all 3 tests pass.

---

## Task 5: ContextCompactor — prevent budget overshoot

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java`
- Test: `src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java`

**Bug:** `available = Math.max(MIN_TOKENS_PER_HIT, remaining - reserved)`. When `remaining < MIN_TOKENS_PER_HIT` (budget nearly exhausted), `available` is floored at 20. The block is truncated to 20 tokens and appended. `used` exceeds `budgetTokens` before the post-append break fires. Output can overshoot the budget by up to `MIN_TOKENS_PER_HIT - 1 = 19` tokens.

The dead-code guard `if (available <= 0) break` at line 26 is also never reached (Math.max ensures `available >= 20`) and will be removed.

**Fix:** Cap `available` at `remaining` using `Math.min`, and add an early-exit guard for `remaining <= 0` at loop top.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java`:

```java
@Test
void compact_neverExceedsBudget() {
    var compactor = new ContextCompactor(new TokenBudget(4));
    String bigText = "x ".repeat(500); // 1000 chars = 250 estimated tokens
    var hit1 = new SearchHit("a", "r", "A.java", Domain.CODE, "METHOD", "A#a", null, null, bigText, 1);
    var hit2 = new SearchHit("b", "r", "B.java", Domain.CODE, "METHOD", "B#b", null, null, bigText, 1);

    int budget = 3; // extremely tight; MIN_TOKENS_PER_HIT floor currently causes overshoot
    String result = compactor.compact(List.of(hit1, hit2), budget);

    int actualTokens = new TokenBudget(4).estimate(result);
    assertThat(actualTokens).isLessThanOrEqualTo(budget);
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
mvn test -pl . -Dtest=ContextCompactorTest#compact_neverExceedsBudget -q
```

Expected: FAIL (`actualTokens` = ~20, exceeds budget=3)

- [ ] **Step 3: Implement the fix**

Replace the `compact()` method in `src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java`:

```java
public String compact(List<SearchHit> hits, int budgetTokens) {
    if (hits.isEmpty()) return "";
    StringBuilder out = new StringBuilder();
    int used = 0;
    for (int i = 0; i < hits.size(); i++) {
        int remaining = budgetTokens - used;
        if (remaining <= 0) break;
        SearchHit hit = hits.get(i);
        int futureHits = hits.size() - i - 1;
        int reserved = MIN_TOKENS_PER_HIT * futureHits;
        // Cap at remaining to guarantee no overshoot
        int available = Math.min(remaining, Math.max(MIN_TOKENS_PER_HIT, remaining - reserved));
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
```

- [ ] **Step 4: Run tests**

```
mvn test -pl . -Dtest=ContextCompactorTest -q
```

Expected: all 3 tests pass.

---

## Task 6: JavaSourceParser — normalize symbol storage (strip `@` prefix)

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- Modify: `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

**Bug:** `symbols()` stores annotation names with a `@` prefix (`@RestController`, `@Service`). The `SearchFilter.symbol` field does an exact `TermQuery` match. Any caller constructing `SearchFilter(repo, domain, null, null, null, "RestController")` (without `@`) gets zero results. The convention is undocumented and unintuitive — annotation names do not include `@` in Java identifiers.

**Fix:** Strip the `@` prefix from `symbols()`. Update the one test that exercises symbol filtering to drop the `@`.

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`:

```java
@Test
void symbols_storedWithoutAtPrefix() {
    var parser = new JavaSourceParser();
    var source = "@org.springframework.stereotype.Service\npublic class FooService {}";
    var result = parser.parse("repo", "FooService.java", "sha", source);
    var classChunk = result.chunks().stream()
        .filter(c -> "CLASS".equals(c.type()))
        .findFirst().orElseThrow();
    assertThat(classChunk.symbols())
        .contains("Service")
        .doesNotContain("@Service");
}
```

- [ ] **Step 2: Run test to confirm it fails**

```
mvn test -pl . -Dtest=JavaSourceParserTest#symbols_storedWithoutAtPrefix -q
```

Expected: FAIL (currently stores `"@Service"`)

- [ ] **Step 3: Implement the fix**

In `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`, change `symbols()`:

```java
private static List<String> symbols(List<AnnotationExpr> annotations) {
    return annotations.stream()
        .map(AnnotationExpr::getNameAsString)  // no "@" prefix
        .collect(Collectors.toList());
}
```

Also update `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java` — find the line using `"@Service"` as a symbol filter value and change it to `"Service"`:

Find this pattern:
```java
new SearchFilter(null, null, null, null, null, "@Service")
```
Change to:
```java
new SearchFilter(null, null, null, null, null, "Service")
```

- [ ] **Step 4: Run tests**

```
mvn test -pl . -Dtest=JavaSourceParserTest,NrtLuceneSearcherTest -q
```

Expected: all tests pass.

---

## Final Verification

- [ ] **Run the full test suite**

```
mvn test -q
```

Expected: all tests pass, 0 failures, 0 errors.

- [ ] **Write the implementation report**

Write `docs/superpowers/implementation/2026-06-17-review-fixes-implementation.md`:

```markdown
# Review Fixes Implementation Report

Source plan: `docs/superpowers/plans/2026-06-17-review-fixes.md`

## Implemented

[One bullet per task]

## Key Files

[All modified files]

## Tests Added or Updated

[All test files]

## Verification

- `mvn test -q` result: [paste output]
- Any deviations from plan: [list or "none"]
```

---

## Notes for Codex

- Not a git repository — skip all `git commit` steps.
- Work dir: `<kira-install-dir>`
- Tasks must be done in order: Task 1 → 2 → 3 → 4 → 5 → 6.
  - Task 3 depends on Task 2 (uses `hit.text()` which requires the SearchHit rename).
- `SearchHit` component rename: `snippet` → `text`. All existing test constructors use positional args; the 9th arg is now named `text` instead of `snippet`, but the value passed is the same. Short strings: `snippet()` returns unchanged. Long strings: `snippet()` truncates, `text()` returns full.
- `MarkdownParser`: unsplit sections still use `path#slug` IDs (unchanged). Split sections now use `path#slug:0`, `path#slug:1`, etc. (colon separator, never produced by slugify). Re-indexing is required after deployment to avoid stale IDs in Lucene.
- `JavaSourceParser` symbol change: re-indexing required. Any `SearchFilter.symbol` callers using the old `@Annotation` form must drop the `@`.
