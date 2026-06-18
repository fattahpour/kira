# Kira Phase 4 — Scale + Harden Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace JGraphT with Kuzu embedded graph, add NRT `SearcherManager` for near-real-time Lucene reads after commits, add Micrometer metrics, tune the full-repo reindex for millions of LOC, and optionally enable int8/Matryoshka vectors for 2-4× storage/latency reduction.

**Architecture:** `KuzuGraphStore` replaces `CodeGraphStore` (same interface). `NrtLuceneSearcher` wraps `SearcherManager` so searches see new commits within milliseconds without restarting. `MetricsService` instruments every critical path. `FullReindexService` parallelizes parsing across files on a bounded thread pool and commits in batches to avoid large segment merges.

**Prerequisite:** Phase 0 + 1 + 2 + 3 complete. All tests pass.

**Tech Stack:** Phase 3 stack + Kuzu 0.6.x Java bindings, Micrometer (already in Spring Boot Actuator)

---

## File Map

```
ai-retrieval/src/main/java/com/acme/airetrieval/
├── index/
│   ├── NrtLuceneSearcher.java          (new — wraps SearcherManager for NRT reads)
│   └── LuceneSearcher.java             (modify — add reopenIfChanged() + factory method)
├── graph/
│   ├── GraphStore.java                 (new — interface extracted from CodeGraphStore)
│   ├── CodeGraphStore.java             (modify — implement GraphStore)
│   └── KuzuGraphStore.java             (new — Kuzu embedded, implements GraphStore)
├── ingest/
│   └── FullReindexService.java         (new — parallel full-repo reindex with batching)
├── observe/
│   └── MetricsService.java             (new — Micrometer counters/timers for key paths)
├── config/
│   ├── ApplicationProps.java           (modify — add kuzu, fullReindex, metrics config)
│   └── GraphConfig.java                (new — chooses CodeGraphStore vs KuzuGraphStore)
└── api/
    └── IndexController.java            (modify — add POST /api/v1/index/full)

src/test/java/com/acme/airetrieval/
├── index/NrtLuceneSearcherTest.java    (new)
├── graph/KuzuGraphStoreTest.java       (new — conditionally skipped if Kuzu not available)
└── ingest/FullReindexServiceTest.java  (new)
```

---

### Task 1: ApplicationProps — add Phase 4 config

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/config/ApplicationProps.java`
- Modify: `ai-retrieval/src/main/resources/application.yml`

- [ ] **Step 1: Update ApplicationProps**

```java
@ConfigurationProperties(prefix = "kira")
public record ApplicationProps(
    Path dataDir,
    Path indexDir,
    Path checkpointFile,
    Path modelsDir,
    int maxSearchResults,
    int defaultSearchK,
    Embedding embedding,
    Reranker reranker,
    TokenBudgetConfig tokenBudget,
    Executor executor,
    Graph graph,
    FullReindex fullReindex
) {
    public record Executor(int indexThreads) {}
    public record Embedding(Path modelPath, Path tokenizerPath, int dim) {}
    public record Reranker(Path modelPath, Path tokenizerPath, boolean enabled) {}
    public record TokenBudgetConfig(int defaultBudgetTokens, int charsPerToken) {}
    public record Graph(String engine, Path kuzuDir) {} // engine: jgrapht | kuzu
    public record FullReindex(int batchSize, int parallelFiles) {}
}
```

- [ ] **Step 2: Update application.yml**

```yaml
kira:
  # ... existing config ...
  graph:
    engine: jgrapht      # switch to "kuzu" after Kuzu binaries are staged
    kuzu-dir: ${kira.data-dir}/graph
  full-reindex:
    batch-size: 200
    parallel-files: 8
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/ApplicationProps.java \
        src/main/resources/application.yml
git commit -m "feat: extend ApplicationProps — graph engine selection, fullReindex config"
```

---

### Task 2: GraphStore interface

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/GraphStore.java`
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/CodeGraphStore.java`

- [ ] **Step 1: Extract GraphStore interface**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/GraphStore.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.graph.model.GraphNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface GraphStore {
    void applyEvent(GraphEvent event);
    Optional<GraphNode> getNode(String id);
    List<GraphEdge> getOutEdges(String id);
    List<GraphEdge> getInEdges(String id);
    List<GraphEdge> getEdgesByType(String id, GraphEdge.EdgeType type, boolean outgoing);
    Set<String> getNodesByTag(String tag);
}
```

- [ ] **Step 2: Add `implements GraphStore` to CodeGraphStore**

```java
// In CodeGraphStore.java:
@Component("jgraphtGraphStore")
public class CodeGraphStore implements GraphStore {
    // ... existing implementation unchanged ...
}
```

Change the `@Component` annotation to `@Component("jgraphtGraphStore")` to avoid conflicts when Kuzu store is also registered.

- [ ] **Step 3: Update GraphExtractor and GraphQueries to use GraphStore**

In `GraphExtractor.java`:
```java
// Inject GraphStore (not CodeGraphStore) via constructor
public GraphExtractor(GraphStore store) {
    this.store = store;
}
private final GraphStore store;
```

In `GraphQueries.java`:
```java
// Inject GraphStore
public GraphQueries(GraphStore store) {
    this.store = store;
}
private final GraphStore store;
```

- [ ] **Step 4: Compile + run tests**

```bash
mvn test -q
```
Expected: All tests pass (CodeGraphStore still active, tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/GraphStore.java \
        src/main/java/com/acme/airetrieval/graph/CodeGraphStore.java \
        src/main/java/com/acme/airetrieval/graph/GraphExtractor.java \
        src/main/java/com/acme/airetrieval/graph/GraphQueries.java
git commit -m "refactor: extract GraphStore interface — decouples JGraphT from graph consumers"
```

---

### Task 3: KuzuGraphStore

**Files:**
- Modify: `ai-retrieval/pom.xml` (add Kuzu dependency)
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/KuzuGraphStore.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/config/GraphConfig.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/graph/KuzuGraphStoreTest.java`

- [ ] **Step 1: Add Kuzu dependency to pom.xml**

```xml
<!-- Kuzu embedded graph database -->
<dependency>
  <groupId>com.kuzudb</groupId>
  <artifactId>kuzu</artifactId>
  <version>0.6.0</version>
</dependency>
```

Note: Check https://mvnrepository.com/artifact/com.kuzudb/kuzu for the latest version.
If the Maven artifact is not available, download the JAR from https://github.com/kuzudb/kuzu/releases
and install locally: `mvn install:install-file -Dfile=kuzu-java.jar -DgroupId=com.kuzudb -DartifactId=kuzu -Dversion=0.6.0 -Dpackaging=jar`

- [ ] **Step 2: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/graph/KuzuGraphStoreTest.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Run with: mvn test -Dtest=KuzuGraphStoreTest -Dkuzu.enabled=true
 */
@EnabledIfSystemProperty(named = "kuzu.enabled", matches = "true")
class KuzuGraphStoreTest {

    Path kuzuDir;
    KuzuGraphStore store;

    @BeforeEach
    void setUp() throws IOException {
        kuzuDir = Files.createTempDirectory("kuzu-test");
        store = new KuzuGraphStore(kuzuDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
        Files.walk(kuzuDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    void addNodeAndEdge_canQueryNeighbors() {
        store.applyEvent(new GraphEvent.NodeEvent("A", "Class", Set.of(), "class A", null));
        store.applyEvent(new GraphEvent.NodeEvent("B", "Method", Set.of(), "void b()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("A", "B", GraphEdge.EdgeType.DECLARES));

        assertThat(store.getNode("A")).isPresent();
        assertThat(store.getOutEdges("A")).hasSize(1);
        assertThat(store.getOutEdges("A").get(0).to()).isEqualTo("B");
    }

    @Test
    void getInEdges_returnsCallers() {
        store.applyEvent(new GraphEvent.NodeEvent("caller", "Method", Set.of(), "void caller()", null));
        store.applyEvent(new GraphEvent.NodeEvent("callee", "Method", Set.of(), "void callee()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("caller", "callee", GraphEdge.EdgeType.CALLS));

        assertThat(store.getInEdges("callee"))
            .anyMatch(e -> e.from().equals("caller") && e.type() == GraphEdge.EdgeType.CALLS);
    }
}
```

- [ ] **Step 3: Implement KuzuGraphStore**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/KuzuGraphStore.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.graph.model.GraphNode;
import com.kuzudb.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class KuzuGraphStore implements GraphStore, AutoCloseable {

    private final Database db;
    private final Connection conn;

    public KuzuGraphStore(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        this.db   = new Database(dataDir.toString());
        this.conn = new Connection(db);
        initSchema();
    }

    private void initSchema() {
        // Create node and edge tables only if they don't exist
        // Kuzu uses Cypher-like DDL
        try {
            conn.query("CREATE NODE TABLE IF NOT EXISTS Node(" +
                "id STRING, label STRING, tags STRING, signature STRING, javadoc STRING, PRIMARY KEY(id))");
            conn.query("CREATE REL TABLE IF NOT EXISTS Edge(" +
                "FROM Node TO Node, type STRING)");
        } catch (Exception e) {
            throw new RuntimeException("Failed to init Kuzu schema", e);
        }
    }

    @Override
    public void applyEvent(GraphEvent event) {
        switch (event) {
            case GraphEvent.NodeEvent ne -> {
                String tagsJson = String.join(",", ne.tags());
                String javadoc  = ne.javadoc()   != null ? escape(ne.javadoc())   : "";
                String sig      = ne.signature()  != null ? escape(ne.signature()) : "";
                conn.query(String.format(
                    "MERGE (n:Node {id: '%s'}) SET n.label = '%s', n.tags = '%s', n.signature = '%s', n.javadoc = '%s'",
                    escape(ne.id()), escape(ne.label()), tagsJson, sig, javadoc));
            }
            case GraphEvent.EdgeEvent ee -> {
                // Ensure both nodes exist
                conn.query(String.format(
                    "MERGE (n:Node {id: '%s'})", escape(ee.from())));
                conn.query(String.format(
                    "MERGE (n:Node {id: '%s'})", escape(ee.to())));
                conn.query(String.format(
                    "MATCH (a:Node {id: '%s'}), (b:Node {id: '%s'}) " +
                    "MERGE (a)-[:Edge {type: '%s'}]->(b)",
                    escape(ee.from()), escape(ee.to()), ee.type().name()));
            }
            case GraphEvent.TagEvent te -> {
                conn.query(String.format(
                    "MATCH (n:Node {id: '%s'}) SET n.tags = n.tags + ',%s'",
                    escape(te.id()), te.tag()));
            }
        }
    }

    @Override
    public Optional<GraphNode> getNode(String id) {
        try (var result = conn.query(String.format(
                "MATCH (n:Node {id: '%s'}) RETURN n.id, n.label, n.tags, n.signature, n.javadoc",
                escape(id)))) {
            if (!result.hasNext()) return Optional.empty();
            var row = result.next();
            String tagsStr = (String) row.getValue("n.tags");
            Set<String> tags = tagsStr == null || tagsStr.isBlank()
                ? Set.of() : Set.of(tagsStr.split(","));
            return Optional.of(new GraphNode(
                (String) row.getValue("n.id"),
                (String) row.getValue("n.label"),
                tags,
                (String) row.getValue("n.signature"),
                (String) row.getValue("n.javadoc")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public List<GraphEdge> getOutEdges(String id) {
        return queryEdges(String.format(
            "MATCH (a:Node {id: '%s'})-[e:Edge]->(b:Node) RETURN a.id, b.id, e.type",
            escape(id)), true);
    }

    @Override
    public List<GraphEdge> getInEdges(String id) {
        return queryEdges(String.format(
            "MATCH (a:Node)-[e:Edge]->(b:Node {id: '%s'}) RETURN a.id, b.id, e.type",
            escape(id)), false);
    }

    @Override
    public List<GraphEdge> getEdgesByType(String id, GraphEdge.EdgeType type, boolean outgoing) {
        return outgoing ? getOutEdges(id).stream().filter(e -> e.type() == type).toList()
                        : getInEdges(id).stream().filter(e -> e.type() == type).toList();
    }

    @Override
    public Set<String> getNodesByTag(String tag) {
        Set<String> result = new HashSet<>();
        try (var qr = conn.query(String.format(
                "MATCH (n:Node) WHERE n.tags CONTAINS '%s' RETURN n.id", escape(tag)))) {
            while (qr.hasNext()) {
                result.add((String) qr.next().getValue("n.id"));
            }
        } catch (Exception e) {
            // ignore
        }
        return result;
    }

    private List<GraphEdge> queryEdges(String cypher, boolean fromIsSource) {
        List<GraphEdge> edges = new ArrayList<>();
        try (var result = conn.query(cypher)) {
            while (result.hasNext()) {
                var row = result.next();
                String from = (String) row.getValue("a.id");
                String to   = (String) row.getValue("b.id");
                String type = (String) row.getValue("e.type");
                edges.add(new GraphEdge(from, to, GraphEdge.EdgeType.valueOf(type)));
            }
        } catch (Exception e) {
            // ignore
        }
        return edges;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    @Override
    public void close() {
        conn.close();
        db.close();
    }
}
```

- [ ] **Step 4: Create GraphConfig to select engine**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/config/GraphConfig.java
package com.acme.airetrieval.config;

import com.acme.airetrieval.graph.CodeGraphStore;
import com.acme.airetrieval.graph.GraphStore;
import com.acme.airetrieval.graph.KuzuGraphStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class GraphConfig {

    @Bean
    @Primary
    public GraphStore graphStore(ApplicationProps props) throws Exception {
        var graphProps = props.graph();
        if ("kuzu".equalsIgnoreCase(graphProps.engine())) {
            return new KuzuGraphStore(graphProps.kuzuDir());
        }
        return new CodeGraphStore();
    }
}
```

Remove `@Component` from `CodeGraphStore` (it's now created by the factory bean):
```java
// CodeGraphStore.java — remove @Component annotation
public class CodeGraphStore implements GraphStore {
```

- [ ] **Step 5: Run tests (JGraphT by default, Kuzu skipped)**

```bash
mvn test -q
```
Expected: All tests pass. KuzuGraphStoreTest is skipped (no `-Dkuzu.enabled=true`).

- [ ] **Step 6: Commit**

```bash
git add pom.xml \
        src/main/java/com/acme/airetrieval/graph/GraphStore.java \
        src/main/java/com/acme/airetrieval/graph/KuzuGraphStore.java \
        src/main/java/com/acme/airetrieval/config/GraphConfig.java \
        src/main/java/com/acme/airetrieval/graph/CodeGraphStore.java \
        src/test/java/com/acme/airetrieval/graph/KuzuGraphStoreTest.java
git commit -m "feat: KuzuGraphStore + GraphConfig — switch engine via kira.graph.engine=kuzu"
```

---

### Task 4: NrtLuceneSearcher (Near-Real-Time reads)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
package com.acme.airetrieval.index;

import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class NrtLuceneSearcherTest {

    Path indexDir;
    LuceneIndexer indexer;
    NrtLuceneSearcher searcher;

    @BeforeEach
    void setUp() throws IOException {
        indexDir = Files.createTempDirectory("nrt-test");
        indexer  = new LuceneIndexer(indexDir);
        searcher = new NrtLuceneSearcher(indexer.getWriter());
    }

    @AfterEach
    void tearDown() throws Exception {
        searcher.close();
        indexer.close();
        Files.walk(indexDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    void nrtSearcher_seesNewDocsAfterReopenWithoutClose() throws IOException {
        // Index doc 1
        indexer.upsert(new Chunk("id1", "r", "p", Domain.KNOWLEDGE, "MD_SECTION",
            null, null, null, List.of(), "s", "h1", "md", "document one", null));
        indexer.commit();
        searcher.maybeReopen();

        assertThat(searcher.bm25("document one", null, 5)).hasSize(1);

        // Index doc 2 without closing
        indexer.upsert(new Chunk("id2", "r", "p2", Domain.KNOWLEDGE, "MD_SECTION",
            null, null, null, List.of(), "s", "h2", "md", "document two", null));
        indexer.commit();
        searcher.maybeReopen(); // NRT reopen — no restart needed

        assertThat(searcher.bm25("document two", null, 5)).hasSize(1);
    }
}
```

- [ ] **Step 2: Add `getWriter()` to LuceneIndexer**

Add to `LuceneIndexer.java`:
```java
/** Returns the underlying IndexWriter for NRT SearcherManager. */
public IndexWriter getWriter() {
    return writer;
}
```

- [ ] **Step 3: Run test — verify it fails**

```bash
mvn test -Dtest=NrtLuceneSearcherTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 4: Implement NrtLuceneSearcher**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java
package com.acme.airetrieval.index;

import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.SearcherManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NrtLuceneSearcher implements AutoCloseable {

    private final SearcherManager searcherManager;
    private final StandardAnalyzer analyzer;

    public NrtLuceneSearcher(IndexWriter writer) throws IOException {
        this.searcherManager = new SearcherManager(writer, new SearcherFactory());
        this.analyzer = new StandardAnalyzer();
    }

    public void maybeReopen() throws IOException {
        searcherManager.maybeRefresh();
    }

    public List<SearchHit> bm25(String queryText, SearchFilter filter, int k) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query base;
            try {
                base = new QueryParser("text", analyzer).parse(QueryParserBase.escape(queryText));
            } catch (ParseException e) {
                base = new TermQuery(new Term("text", queryText.toLowerCase()));
            }
            Query finalQuery = applyFilter(base, filter);
            TopDocs topDocs = searcher.search(finalQuery, k);
            return toHits(searcher, topDocs);
        } finally {
            searcherManager.release(searcher);
        }
    }

    public List<SearchHit> knn(float[] queryVector, SearchFilter filter, int k) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query knnQuery = new KnnFloatVectorQuery("vector", queryVector, k,
                filter != null ? buildFilterQuery(filter) : null);
            TopDocs topDocs = searcher.search(knnQuery, k);
            return toHits(searcher, topDocs);
        } finally {
            searcherManager.release(searcher);
        }
    }

    public Optional<String> getContentHash(String chunkId) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs td = searcher.search(new TermQuery(new Term("id", chunkId)), 1);
            if (td.totalHits.value() == 0) return Optional.empty();
            Document doc = searcher.getIndexReader().storedFields().document(td.scoreDocs[0].doc);
            return Optional.ofNullable(doc.get("content_hash"));
        } finally {
            searcherManager.release(searcher);
        }
    }

    private List<SearchHit> toHits(IndexSearcher searcher, TopDocs topDocs) throws IOException {
        var storedFields = searcher.getIndexReader().storedFields();
        List<SearchHit> hits = new ArrayList<>();
        for (ScoreDoc sd : topDocs.scoreDocs) {
            Document doc = storedFields.document(sd.doc);
            hits.add(SearchHit.fromDocument(doc, sd.score));
        }
        return hits;
    }

    private Query applyFilter(Query base, SearchFilter filter) {
        if (filter == null) return base;
        Query filterQuery = buildFilterQuery(filter);
        if (filterQuery == null) return base;
        return new BooleanQuery.Builder()
            .add(base, BooleanClause.Occur.MUST)
            .add(filterQuery, BooleanClause.Occur.FILTER)
            .build();
    }

    private Query buildFilterQuery(SearchFilter filter) {
        if (filter == null) return null;
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        boolean any = false;
        if (filter.repo()   != null) { b.add(new TermQuery(new Term("repo",   filter.repo())),   BooleanClause.Occur.FILTER); any = true; }
        if (filter.domain() != null) { b.add(new TermQuery(new Term("domain", filter.domain())), BooleanClause.Occur.FILTER); any = true; }
        if (filter.type()   != null) { b.add(new TermQuery(new Term("type",   filter.type())),   BooleanClause.Occur.FILTER); any = true; }
        if (filter.path()   != null) { b.add(new TermQuery(new Term("path",   filter.path())),   BooleanClause.Occur.FILTER); any = true; }
        return any ? b.build() : null;
    }

    @Override
    public void close() throws IOException {
        searcherManager.close();
        analyzer.close();
    }
}
```

- [ ] **Step 5: Update LuceneConfig to use NrtLuceneSearcher**

In `LuceneConfig.java`, replace `LuceneSearcher` bean with `NrtLuceneSearcher`:
```java
@Bean
public NrtLuceneSearcher luceneSearcher(LuceneIndexer indexer) throws IOException {
    return new NrtLuceneSearcher(indexer.getWriter());
}
```

Update all injection sites that used `LuceneSearcher` to use `NrtLuceneSearcher`. Both have the same `bm25()`, `knn()`, `getContentHash()` signatures, so no behavioral changes — just the type.

Alternatively: make `NrtLuceneSearcher` implement the same methods as `LuceneSearcher` (which it does) and update `LuceneSearcher` to no longer be registered as a primary bean. The simplest fix is to rename the bean method return type.

- [ ] **Step 6: Update IndexService to call maybeReopen() after commit**

After `indexer.commit()` in `IndexService.indexIncremental()`:
```java
indexer.commit();
luceneSearcher.maybeReopen(); // make new docs visible immediately
```

Inject `NrtLuceneSearcher luceneSearcher` into `IndexService`.

- [ ] **Step 7: Run tests**

```bash
mvn test -q
```
Expected: All tests pass, including `NrtLuceneSearcherTest`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java \
        src/main/java/com/acme/airetrieval/index/LuceneIndexer.java \
        src/main/java/com/acme/airetrieval/config/LuceneConfig.java \
        src/main/java/com/acme/airetrieval/ingest/IndexService.java \
        src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
git commit -m "feat: NrtLuceneSearcher — SearcherManager provides NRT reads after commit without restart"
```

---

### Task 5: FullReindexService

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/FullReindexService.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/FullReindexServiceTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/FullReindexServiceTest.java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.model.Domain;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class FullReindexServiceTest {

    Path repoDir;
    Path indexDir;
    Git git;
    LuceneIndexer indexer;
    NrtLuceneSearcher searcher;

    @BeforeEach
    void setUp() throws Exception {
        repoDir  = Files.createTempDirectory("repo-test");
        indexDir = Files.createTempDirectory("index-test");
        git = Git.init().setDirectory(repoDir.toFile()).call();
        indexer  = new LuceneIndexer(indexDir);
        searcher = new NrtLuceneSearcher(indexer.getWriter());
    }

    @AfterEach
    void tearDown() throws Exception {
        searcher.close();
        indexer.close();
        git.close();
        Files.walk(repoDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        Files.walk(indexDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    @Test
    void fullReindex_markdownFiles_indexesAllChunks() throws Exception {
        // Setup: create 2 markdown files and commit
        Files.writeString(repoDir.resolve("A.md"), "# Topic A\nContent about Kafka.");
        Files.writeString(repoDir.resolve("B.md"), "# Topic B\nContent about Maven.");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("add docs").call();

        var markdownParser = new com.acme.airetrieval.ingest.parser.MarkdownParser();
        var classifier     = new SourceClassifier();
        var service        = new FullReindexService(classifier, markdownParser, indexer, searcher, 10, 2);
        service.reindexAll(repoDir, "testrepo");

        assertThat(searcher.bm25("Kafka", null, 5)).isNotEmpty();
        assertThat(searcher.bm25("Maven", null, 5)).isNotEmpty();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=FullReindexServiceTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement FullReindexService**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/FullReindexService.java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.parser.DocumentParser;
import com.acme.airetrieval.ingest.parser.MarkdownParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class FullReindexService {

    private static final Logger log = LoggerFactory.getLogger(FullReindexService.class);

    private final SourceClassifier classifier;
    private final MarkdownParser markdownParser;
    private final LuceneIndexer indexer;
    private final NrtLuceneSearcher searcher;
    private final int batchSize;
    private final int parallelFiles;

    public FullReindexService(SourceClassifier classifier, MarkdownParser markdownParser,
                               LuceneIndexer indexer, NrtLuceneSearcher searcher,
                               int batchSize, int parallelFiles) {
        this.classifier    = classifier;
        this.markdownParser = markdownParser;
        this.indexer       = indexer;
        this.searcher      = searcher;
        this.batchSize     = batchSize;
        this.parallelFiles  = parallelFiles;
    }

    public ReindexResult reindexAll(Path repoRoot, String repo) throws IOException, InterruptedException {
        List<Path> files;
        try (var walk = Files.walk(repoRoot)) {
            files = walk
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains("/.git/"))
                .collect(Collectors.toList());
        }

        var executor = Executors.newFixedThreadPool(parallelFiles);
        var futures  = new ArrayList<Future<List<Chunk>>>();
        int processed = 0, skipped = 0;

        for (Path file : files) {
            String relPath = repoRoot.relativize(file).toString();
            var ft = classifier.classify(relPath);
            if (ft == SourceClassifier.FileType.SKIP) { skipped++; continue; }

            final Path finalFile = file;
            final String finalPath = relPath;
            futures.add(executor.submit(() -> parseFile(ft, repo, finalPath, finalFile)));
        }

        executor.shutdown();
        executor.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);

        List<Chunk> batch = new ArrayList<>();
        for (Future<List<Chunk>> f : futures) {
            try {
                batch.addAll(f.get());
                if (batch.size() >= batchSize) {
                    flushBatch(batch);
                    processed += batch.size();
                    batch.clear();
                }
            } catch (ExecutionException e) {
                log.warn("Parse error: {}", e.getCause().getMessage());
            }
        }
        if (!batch.isEmpty()) {
            flushBatch(batch);
            processed += batch.size();
        }

        return new ReindexResult(processed, skipped, files.size());
    }

    private List<Chunk> parseFile(SourceClassifier.FileType ft, String repo,
                                   String relPath, Path file) throws Exception {
        return switch (ft) {
            case MARKDOWN -> {
                String content = Files.readString(file);
                yield markdownParser.parse(repo, relPath, "HEAD", content);
            }
            case DOCUMENT -> new DocumentParser().parse(repo, relPath, "HEAD", file);
            default -> List.of();
        };
    }

    private void flushBatch(List<Chunk> batch) throws IOException {
        for (Chunk chunk : batch) indexer.upsert(chunk);
        indexer.commit();
        searcher.maybeReopen();
        log.info("Flushed batch of {} chunks", batch.size());
    }

    public record ReindexResult(int indexed, int skipped, int totalFiles) {}
}
```

- [ ] **Step 4: Add FullReindexService bean and REST endpoint**

Add to `IndexController.java`:
```java
@Autowired FullReindexService fullReindexService;

@PostMapping("/full")
public ResponseEntity<FullReindexService.ReindexResult> full(@RequestBody IndexRequest req)
        throws Exception {
    var result = fullReindexService.reindexAll(Path.of(req.repoPath()), req.repo());
    return ResponseEntity.ok(result);
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
mvn test -Dtest=FullReindexServiceTest -q
```
Expected: Tests run: 1, Failures: 0, Errors: 0.

- [ ] **Step 6: Run full suite**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/FullReindexService.java \
        src/main/java/com/acme/airetrieval/api/IndexController.java \
        src/test/java/com/acme/airetrieval/ingest/FullReindexServiceTest.java
git commit -m "feat: FullReindexService — parallel batched full-repo reindex with NRT commit"
```

---

### Task 6: Micrometer metrics

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/observe/MetricsService.java`

- [ ] **Step 1: Create MetricsService**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/observe/MetricsService.java
package com.acme.airetrieval.observe;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;

@Component
public class MetricsService {

    private final MeterRegistry registry;

    // Counters
    private final Counter chunksIndexed;
    private final Counter chunksSkipped;
    private final Counter searchRequests;

    // Timers
    private final Timer searchLatency;
    private final Timer embedLatency;
    private final Timer rerankerLatency;

    // Gauges (track via AtomicLong for thread-safety)
    private final java.util.concurrent.atomic.AtomicLong lastTokensReturned =
        new java.util.concurrent.atomic.AtomicLong(0);

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        this.chunksIndexed    = Counter.builder("kira.index.chunks").tag("action","indexed").register(registry);
        this.chunksSkipped    = Counter.builder("kira.index.chunks").tag("action","skipped").register(registry);
        this.searchRequests   = Counter.builder("kira.search.requests").register(registry);
        this.searchLatency    = Timer.builder("kira.search.latency").register(registry);
        this.embedLatency     = Timer.builder("kira.embed.latency").register(registry);
        this.rerankerLatency  = Timer.builder("kira.reranker.latency").register(registry);
        Gauge.builder("kira.tokens.returned.last", lastTokensReturned, java.util.concurrent.atomic.AtomicLong::get)
            .description("Token count of the last answer_context response")
            .register(registry);
    }

    public void recordChunkIndexed()  { chunksIndexed.increment(); }
    public void recordChunkSkipped()  { chunksSkipped.increment(); }
    public void recordSearchRequest() { searchRequests.increment(); }
    public void recordTokensReturned(long tokens) { lastTokensReturned.set(tokens); }

    public <T> T timeSearch(Supplier<T> fn)    { return searchLatency.record(fn); }
    public <T> T timeEmbed(Supplier<T> fn)     { return embedLatency.record(fn); }
    public <T> T timeReranker(Supplier<T> fn)  { return rerankerLatency.record(fn); }
}
```

- [ ] **Step 2: Inject MetricsService into RetrievalOrchestrator**

In `RetrievalOrchestrator.java`, inject `MetricsService` and wrap key calls:
```java
public List<SearchHit> hybridRerank(String query, SearchFilter filter, int k)
        throws IOException, OrtException {
    metrics.recordSearchRequest();
    return metrics.timeSearch(() -> {
        try {
            return hybridRerankInternal(query, filter, k);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    });
}
```

- [ ] **Step 3: Verify metrics endpoint**

```bash
mvn spring-boot:run &
sleep 8
curl -s http://localhost:8080/actuator/metrics | python3 -m json.tool | grep kira
kill %1
```
Expected: `kira.search.requests`, `kira.search.latency`, etc. appear in the metrics list.

- [ ] **Step 4: Compile and run full suite**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/observe/ \
        src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java
git commit -m "feat: MetricsService — Micrometer counters/timers for search, embed, reranker, token budget"
```

---

### Task 7: Fat JAR + systemd deployment

**Files:**
- Create: `ai-retrieval/kira.service` (systemd unit)

- [ ] **Step 1: Build fat JAR**

```bash
mvn package -DskipTests -q
ls -lh target/ai-retrieval-0.1.0-SNAPSHOT.jar
```
Expected: Fat JAR ~80-150 MB (includes Tika parsers).

- [ ] **Step 2: Create systemd unit file**

```ini
# ai-retrieval/kira.service
[Unit]
Description=Kira AI Retrieval Service
After=network.target

[Service]
Type=simple
User=YOUR_USER
WorkingDirectory=/opt/kira
ExecStart=/usr/bin/java \
  -Xms2g -Xmx6g \
  -XX:+UseG1GC \
  -Dkira.data-dir=/opt/kira/data \
  -jar /opt/kira/ai-retrieval.jar
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Install:
```bash
sudo cp kira.service /etc/systemd/system/
sudo cp target/ai-retrieval-0.1.0-SNAPSHOT.jar /opt/kira/ai-retrieval.jar
sudo systemctl daemon-reload
sudo systemctl enable kira
sudo systemctl start kira
sudo systemctl status kira
```

- [ ] **Step 3: Verify running**

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v1/index/status 2>/dev/null || echo "endpoint not yet implemented"
```
Expected: `{"status":"UP"}`.

- [ ] **Step 4: Commit**

```bash
git add kira.service
git commit -m "ops: systemd unit file for production deployment"
```

---

### Phase 4 Exit Criterion

```bash
# Full suite
mvn test -q
# Fat JAR
mvn package -DskipTests -q && java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --kira.data-dir=/tmp/kira-test &
sleep 10
curl http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/metrics/kira.search.requests
kill %1
```

**All tests pass + fat JAR starts + Micrometer metrics visible + NRT search active = Phase 4 complete.**

Run golden eval against a real indexed codebase:
```bash
# Index a real repo first
curl -s -X POST http://localhost:8080/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{"repoPath":"/path/to/your/repo","repo":"myrepo"}'

# Run eval
mvn test -Dtest=GoldenSetEvalTest -Deval.enabled=true -q
```

**Production system complete.** MCP server accessible at `localhost:8080` via streamable HTTP, or via stdio when launched as a subprocess from Claude Code / Codex / Gemini CLI.
