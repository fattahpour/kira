# Kira Phase 1 — Semantic Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ONNX CPU embeddings (bge-small-en-v1.5), Lucene KNN vector index, and hybrid BM25⊕KNN search fused with Reciprocal Rank Fusion. Incremental indexer skips re-embedding when `contentHash` is unchanged.

**Architecture:** `OnnxEmbeddingModel` tokenizes + mean-pools + L2-normalizes text offline. `LuceneIndexer` gains a `KnnFloatVectorField`. `HybridSearch` runs BM25 and KNN in parallel and fuses results with RRF. `RetrievalOrchestrator` wraps hybrid search and is the single entry point for all retrieval. A golden eval harness measures accuracy before/after every change.

**Prerequisite:** Phase 0 complete. All Phase 0 tests pass.

**Tech Stack:** Phase 0 stack + ONNX Runtime Java 1.19.2, DJL HuggingFace Tokenizers 0.28.0, bge-small-en-v1.5 ONNX model (pre-staged at `models/bge-small.onnx` + `models/tokenizer.json`)

**Model download (run once before starting):**
```bash
# Download bge-small-en-v1.5 in ONNX format
pip install huggingface_hub --quiet
python3 -c "
from huggingface_hub import snapshot_download
snapshot_download(repo_id='BAAI/bge-small-en-v1.5', local_dir='ai-retrieval/src/main/resources/models',
                  allow_patterns=['onnx/model.onnx','tokenizer.json','tokenizer_config.json'])
"
mkdir -p ~/.kira/data/models
cp ai-retrieval/src/main/resources/models/onnx/model.onnx ~/.kira/data/models/bge-small.onnx
cp ai-retrieval/src/main/resources/models/tokenizer.json ~/.kira/data/models/tokenizer.json
```

---

## File Map

```
ai-retrieval/src/main/java/com/acme/airetrieval/
├── config/
│   └── OnnxConfig.java                    (new — creates EmbeddingModel bean, paths from ApplicationProps)
├── embed/
│   ├── EmbeddingModel.java                (new — interface)
│   └── OnnxEmbeddingModel.java            (new — ORT + HF tokenizer + mean-pool + L2-norm)
├── index/
│   ├── LuceneIndexer.java                 (modify — add KnnFloatVectorField to upsert)
│   ├── LuceneSearcher.java                (modify — add knn() method)
│   └── HybridSearch.java                  (new — BM25⊕KNN→RRF)
├── retrieve/
│   └── RetrievalOrchestrator.java         (new — single entry point for hybrid search)
└── config/
    └── ApplicationProps.java              (modify — add modelsDir, embeddingDim)

src/test/java/com/acme/airetrieval/
├── embed/OnnxEmbeddingModelTest.java      (new)
├── index/HybridSearchTest.java            (new)
└── eval/GoldenSetEvalTest.java            (new — golden query set baseline)
```

---

### Task 1: Add ONNX + DJL dependencies + ApplicationProps update

**Files:**
- Modify: `ai-retrieval/pom.xml`
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/config/ApplicationProps.java`
- Modify: `ai-retrieval/src/main/resources/application.yml`

- [ ] **Step 1: Add dependencies to pom.xml**

Add inside `<dependencies>`:
```xml
<!-- ONNX Runtime Java -->
<dependency>
  <groupId>com.microsoft.onnxruntime</groupId>
  <artifactId>onnxruntime</artifactId>
  <version>${onnxruntime.version}</version>
</dependency>

<!-- DJL HuggingFace Tokenizers (CPU tokenizer for ONNX models) -->
<dependency>
  <groupId>ai.djl.huggingface</groupId>
  <artifactId>tokenizers</artifactId>
  <version>${djl.version}</version>
</dependency>

<!-- Lucene KNN support (already in lucene-core, but add lucene-backward-codecs for safety) -->
<dependency>
  <groupId>org.apache.lucene</groupId>
  <artifactId>lucene-backward-codecs</artifactId>
  <version>${lucene.version}</version>
</dependency>
```

- [ ] **Step 2: Update ApplicationProps to include model paths and embedding dim**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/config/ApplicationProps.java
package com.acme.airetrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "kira")
public record ApplicationProps(
    Path dataDir,
    Path indexDir,
    Path checkpointFile,
    Path modelsDir,
    int maxSearchResults,
    int defaultSearchK,
    Embedding embedding,
    Executor executor
) {
    public record Executor(int indexThreads) {}
    public record Embedding(Path modelPath, Path tokenizerPath, int dim) {}
}
```

- [ ] **Step 3: Update application.yml**

```yaml
kira:
  data-dir: ${user.home}/.kira/data
  index-dir: ${kira.data-dir}/lucene
  checkpoint-file: ${kira.data-dir}/checkpoint.json
  models-dir: ${kira.data-dir}/models
  max-search-results: 50
  default-search-k: 10
  embedding:
    model-path: ${kira.models-dir}/bge-small.onnx
    tokenizer-path: ${kira.models-dir}/tokenizer.json
    dim: 384
  executor:
    index-threads: 4
```

- [ ] **Step 4: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/acme/airetrieval/config/ApplicationProps.java \
        src/main/resources/application.yml
git commit -m "feat: add ONNX Runtime + DJL tokenizer deps; extend ApplicationProps for model paths"
```

---

### Task 2: EmbeddingModel interface + OnnxEmbeddingModel

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/embed/EmbeddingModel.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/embed/OnnxEmbeddingModel.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/embed/OnnxEmbeddingModelTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/embed/OnnxEmbeddingModelTest.java
package com.acme.airetrieval.embed;

import org.junit.jupiter.api.*;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnnxEmbeddingModelTest {

    static OnnxEmbeddingModel model;

    @BeforeAll
    static void setUp() throws Exception {
        Path onnxModel = Path.of(System.getProperty("user.home"), ".kira/data/models/bge-small.onnx");
        Path tokenizer = Path.of(System.getProperty("user.home"), ".kira/data/models/tokenizer.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(onnxModel.toFile().exists(),
            "bge-small.onnx not found at " + onnxModel + " — run model download script first");
        model = new OnnxEmbeddingModel(onnxModel, tokenizer);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (model != null) model.close();
    }

    @Test
    @Order(1)
    void embed_shortText_returns384DimVector() throws Exception {
        float[] vec = model.embed("Hello world");
        assertThat(vec).hasSize(384);
    }

    @Test
    @Order(2)
    void embed_vector_isL2Normalized() throws Exception {
        float[] vec = model.embed("Kafka consumer lag");
        double norm = 0;
        for (float v : vec) norm += v * v;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    @Order(3)
    void embed_similarTexts_highCosineSimilarity() throws Exception {
        float[] a = model.embed("Install Maven dependencies");
        float[] b = model.embed("Add Maven dependency to pom.xml");
        float[] c = model.embed("Configure Kafka topic partitions");
        assertThat(cosine(a, b)).isGreaterThan(cosine(a, c));
    }

    @Test
    @Order(4)
    void embed_longText_doesNotThrow() throws Exception {
        String longText = "word ".repeat(600);
        assertThatCode(() -> model.embed(longText)).doesNotThrowAnyException();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // already L2-normalized, so cosine = dot product
    }

    private static org.assertj.core.data.Offset<Double> within(double d) {
        return org.assertj.core.data.Offset.offset(d);
    }
}
```

- [ ] **Step 2: Run test — verify it fails (or skips if model not staged)**

```bash
mvn test -Dtest=OnnxEmbeddingModelTest -q 2>&1 | tail -10
```
Expected: FAIL with `ClassNotFoundException: OnnxEmbeddingModel` (or SKIP if model absent).

- [ ] **Step 3: Create EmbeddingModel interface**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/embed/EmbeddingModel.java
package com.acme.airetrieval.embed;

import ai.onnxruntime.OrtException;

public interface EmbeddingModel extends AutoCloseable {
    float[] embed(String text) throws OrtException;

    default float[][] embedBatch(String[] texts) throws OrtException {
        float[][] result = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) result[i] = embed(texts[i]);
        return result;
    }
}
```

- [ ] **Step 4: Implement OnnxEmbeddingModel**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/embed/OnnxEmbeddingModel.java
package com.acme.airetrieval.embed;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;

public final class OnnxEmbeddingModel implements EmbeddingModel {

    private static final int MAX_TOKENS = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public OnnxEmbeddingModel(Path modelPath, Path tokenizerPath) throws OrtException, IOException {
        this.env = OrtEnvironment.getEnvironment();
        var opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath.toString(), opts);
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    }

    @Override
    public float[] embed(String text) throws OrtException {
        Encoding enc = tokenizer.encode(text, true); // truncate=true
        long[] ids  = enc.getIds();
        long[] mask = enc.getAttentionMask();

        // Truncate to MAX_TOKENS if needed
        if (ids.length > MAX_TOKENS) {
            ids  = truncate(ids,  MAX_TOKENS);
            mask = truncate(mask, MAX_TOKENS);
        }

        long[] shape = {1L, ids.length};
        long[] typeIds = new long[ids.length]; // all zeros for single-sequence

        try (var inputIds  = OnnxTensor.createTensor(env, LongBuffer.wrap(ids),     shape);
             var attnMask  = OnnxTensor.createTensor(env, LongBuffer.wrap(mask),    shape);
             var tokenType = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape);
             var result    = session.run(Map.of(
                 "input_ids",      inputIds,
                 "attention_mask", attnMask,
                 "token_type_ids", tokenType))) {

            float[][][] lastHidden = (float[][][]) result.get(0).getValue(); // [1][seq][hidden]
            return l2Normalize(meanPool(lastHidden[0], mask));
        }
    }

    private static float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        int hidden = tokenEmbeddings[0].length;
        float[] sum = new float[hidden];
        long count = 0;
        for (int t = 0; t < tokenEmbeddings.length; t++) {
            if (attentionMask[t] == 1L) {
                count++;
                for (int j = 0; j < hidden; j++) sum[j] += tokenEmbeddings[t][j];
            }
        }
        if (count == 0) return sum;
        for (int j = 0; j < hidden; j++) sum[j] /= count;
        return sum;
    }

    private static float[] l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) norm += (double) v * v;
        norm = Math.sqrt(norm) + 1e-12;
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / norm);
        return vector;
    }

    private static long[] truncate(long[] arr, int max) {
        long[] result = new long[max];
        System.arraycopy(arr, 0, result, 0, max);
        return result;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
```

- [ ] **Step 5: Run test — verify it passes (requires model staged)**

```bash
mvn test -Dtest=OnnxEmbeddingModelTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0 (or 4 SKIPPED if model not staged yet).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/embed/ \
        src/test/java/com/acme/airetrieval/embed/OnnxEmbeddingModelTest.java
git commit -m "feat: OnnxEmbeddingModel — CPU ONNX inference with mean-pool + L2-normalize"
```

---

### Task 3: OnnxConfig bean + wire embedding into ApplicationProps

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/config/OnnxConfig.java`

- [ ] **Step 1: Create OnnxConfig**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/config/OnnxConfig.java
package com.acme.airetrieval.config;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.embed.OnnxEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OnnxConfig {

    private static final Logger log = LoggerFactory.getLogger(OnnxConfig.class);

    @Bean(destroyMethod = "close")
    public EmbeddingModel embeddingModel(ApplicationProps props) throws Exception {
        var emb = props.embedding();
        log.info("Loading ONNX embedding model from {}", emb.modelPath());
        return new OnnxEmbeddingModel(emb.modelPath(), emb.tokenizerPath());
    }
}
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/OnnxConfig.java
git commit -m "feat: OnnxConfig — creates EmbeddingModel bean from ApplicationProps paths"
```

---

### Task 4: Lucene KNN field — update schema

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/index/LuceneIndexer.java`
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/index/LuceneSearcher.java`

- [ ] **Step 1: Update LuceneIndexer to write vector field**

In `upsert(Chunk chunk)`, add after the existing fields:
```java
// Inside upsert(), after the TextField add:
if (chunk.vector() != null) {
    doc.add(new KnnFloatVectorField("vector", chunk.vector(),
        VectorSimilarityFunction.COSINE));
}
```

Add import at top:
```java
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.VectorSimilarityFunction;
```

- [ ] **Step 2: Add KNN search method to LuceneSearcher**

Add method to `LuceneSearcher`:
```java
public List<SearchHit> knn(float[] queryVector, SearchFilter filter, int k) throws IOException {
    Query knnQuery = new KnnFloatVectorQuery("vector", queryVector, k,
        filter != null ? buildFilterQuery(filter) : null);
    TopDocs topDocs = searcher.search(knnQuery, k);
    return toHits(topDocs);
}

private Query buildFilterQuery(SearchFilter filter) {
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    boolean hasFilter = false;
    if (filter.repo() != null) {
        builder.add(new TermQuery(new Term("repo", filter.repo())), BooleanClause.Occur.FILTER);
        hasFilter = true;
    }
    if (filter.domain() != null) {
        builder.add(new TermQuery(new Term("domain", filter.domain())), BooleanClause.Occur.FILTER);
        hasFilter = true;
    }
    if (filter.type() != null) {
        builder.add(new TermQuery(new Term("type", filter.type())), BooleanClause.Occur.FILTER);
        hasFilter = true;
    }
    return hasFilter ? builder.build() : null;
}
```

Add import:
```java
import org.apache.lucene.search.KnnFloatVectorQuery;
```

Also extract the existing BM25 filter logic to use `buildFilterQuery` to avoid duplication:
```java
// In bm25(), replace inline filter building with:
if (filter != null) {
    Query filterQuery = buildFilterQuery(filter);
    if (filterQuery != null) {
        base = new BooleanQuery.Builder()
            .add(base, BooleanClause.Occur.MUST)
            .add(filterQuery, BooleanClause.Occur.FILTER)
            .build();
    }
}
```

- [ ] **Step 3: Run existing tests to verify nothing broke**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/LuceneIndexer.java \
        src/main/java/com/acme/airetrieval/index/LuceneSearcher.java
git commit -m "feat: add KNN vector field to Lucene schema; LuceneSearcher gains knn() method"
```

---

### Task 5: HybridSearch (BM25⊕KNN with RRF)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/index/HybridSearch.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/index/HybridSearchTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/index/HybridSearchTest.java
package com.acme.airetrieval.index;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class HybridSearchTest {

    private Path indexDir;
    private LuceneIndexer indexer;
    private LuceneSearcher searcher;
    private EmbeddingModel embeddingModel;
    private HybridSearch hybrid;

    @BeforeEach
    void setUp() throws Exception {
        indexDir = Files.createTempDirectory("hybrid-test");
        indexer  = new LuceneIndexer(indexDir);

        // Stub embedding model — returns fixed vectors per text
        embeddingModel = text -> {
            float[] v = new float[4]; // tiny dim for test
            v[0] = text.contains("kafka") ? 1.0f : 0.0f;
            v[1] = text.contains("stream") ? 1.0f : 0.0f;
            v[2] = text.contains("maven") ? 1.0f : 0.0f;
            v[3] = 0.0f;
            return l2(v);
        };

        searcher = new LuceneSearcher(indexDir);
        hybrid   = new HybridSearch(searcher, embeddingModel);
    }

    @AfterEach
    void tearDown() throws Exception {
        hybrid.close();
        indexer.close();
        Files.walk(indexDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    private Chunk chunk(String id, String text, float[] vector) {
        return new Chunk(id, "r", "p/" + id, Domain.KNOWLEDGE, "MD_SECTION",
            null, null, null, List.of(), "s", "h", "md", text, vector);
    }

    @Test
    void search_fusesRanksFromBm25AndKnn() throws Exception {
        float[] kafkaVec = new float[]{1f, 1f, 0f, 0f};
        float[] mavenVec = new float[]{0f, 0f, 1f, 0f};
        indexer.upsert(chunk("kafka-doc", "kafka stream processing platform", l2(kafkaVec)));
        indexer.upsert(chunk("maven-doc", "maven build tool for java", l2(mavenVec)));
        indexer.commit();

        // Re-open searcher after commit to see the data
        searcher.close();
        searcher = new LuceneSearcher(indexDir);
        hybrid   = new HybridSearch(searcher, embeddingModel);

        List<SearchHit> hits = hybrid.search("kafka stream", null, 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).id()).isEqualTo("kafka-doc");
    }

    @Test
    void search_emptyIndex_returnsEmpty() throws Exception {
        indexer.commit();
        searcher.close();
        searcher = new LuceneSearcher(indexDir);
        hybrid   = new HybridSearch(searcher, embeddingModel);

        List<SearchHit> hits = hybrid.search("anything", null, 5);
        assertThat(hits).isEmpty();
    }

    private static float[] l2(float[] v) {
        double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n) + 1e-12;
        for (int i = 0; i < v.length; i++) v[i] = (float)(v[i] / n);
        return v;
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=HybridSearchTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement HybridSearch**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/index/HybridSearch.java
package com.acme.airetrieval.index;

import ai.onnxruntime.OrtException;
import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;

import java.io.IOException;
import java.util.*;

public final class HybridSearch implements AutoCloseable {

    private static final int CANDIDATE_K = 50;
    private static final int RRF_K0 = 60;

    private final LuceneSearcher searcher;
    private final EmbeddingModel embedding;

    public HybridSearch(LuceneSearcher searcher, EmbeddingModel embedding) {
        this.searcher  = searcher;
        this.embedding = embedding;
    }

    public List<SearchHit> search(String query, SearchFilter filter, int topN)
            throws IOException, OrtException {

        float[] queryVec = embedding.embed(query);

        List<SearchHit> bm25Hits = searcher.bm25(query, filter, CANDIDATE_K);
        List<SearchHit> knnHits  = searcher.knn(queryVec, filter, CANDIDATE_K);

        return rrf(bm25Hits, knnHits, topN);
    }

    private static List<SearchHit> rrf(List<SearchHit> bm25, List<SearchHit> knn, int topN) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, SearchHit> byId = new LinkedHashMap<>();

        accumulateRrf(scores, byId, bm25);
        accumulateRrf(scores, byId, knn);

        return scores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topN)
            .map(e -> byId.get(e.getKey()))
            .filter(Objects::nonNull)
            .toList();
    }

    private static void accumulateRrf(Map<String, Double> scores,
                                       Map<String, SearchHit> byId,
                                       List<SearchHit> hits) {
        int rank = 1;
        for (SearchHit hit : hits) {
            scores.merge(hit.id(), 1.0 / (RRF_K0 + rank), Double::sum);
            byId.putIfAbsent(hit.id(), hit);
            rank++;
        }
    }

    @Override
    public void close() throws Exception {
        searcher.close();
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
mvn test -Dtest=HybridSearchTest -q
```
Expected: Tests run: 2, Failures: 0, Errors: 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/HybridSearch.java \
        src/test/java/com/acme/airetrieval/index/HybridSearchTest.java
git commit -m "feat: HybridSearch — BM25⊕KNN fused with Reciprocal Rank Fusion"
```

---

### Task 6: RetrievalOrchestrator

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`

- [ ] **Step 1: Create RetrievalOrchestrator**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java
package com.acme.airetrieval.retrieve;

import ai.onnxruntime.OrtException;
import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.index.HybridSearch;
import com.acme.airetrieval.index.LuceneSearcher;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class RetrievalOrchestrator {

    private final HybridSearch hybridSearch;

    public RetrievalOrchestrator(LuceneSearcher searcher, EmbeddingModel embedding) {
        this.hybridSearch = new HybridSearch(searcher, embedding);
    }

    public List<SearchHit> hybrid(String query, SearchFilter filter, int k)
            throws IOException, OrtException {
        return hybridSearch.search(query, filter, k);
    }

    public List<SearchHit> bm25Only(String query, SearchFilter filter, int k)
            throws IOException {
        return hybridSearch.bm25Only(query, filter, k);
    }
}
```

Add `bm25Only` delegate to `HybridSearch`:
```java
// In HybridSearch.java, add:
public List<SearchHit> bm25Only(String query, SearchFilter filter, int k) throws IOException {
    return searcher.bm25(query, filter, k);
}
```

- [ ] **Step 2: Update SearchController to use RetrievalOrchestrator**

Modify `SearchController` to use `RetrievalOrchestrator` instead of `LuceneSearcher` directly:
```java
// Updated SearchController.java
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final RetrievalOrchestrator retrieval;
    private final ApplicationProps props;

    public SearchController(RetrievalOrchestrator retrieval, ApplicationProps props) {
        this.retrieval = retrieval;
        this.props = props;
    }

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest req) throws Exception {
        if (req.query() == null || req.query().isBlank()) return ResponseEntity.badRequest().build();
        int k = req.k() != null ? Math.min(req.k(), props.maxSearchResults()) : props.defaultSearchK();
        var filter = new SearchFilter(req.repo(), req.domain(), req.type(), null);

        String mode = req.mode() != null ? req.mode() : "hybrid";
        List<SearchHit> hits = switch (mode) {
            case "bm25" -> retrieval.bm25Only(req.query(), filter, k);
            default     -> retrieval.hybrid(req.query(), filter, k);
        };
        return ResponseEntity.ok(new SearchResponse(hits, hits.size()));
    }
}
```

Update `SearchRequest` to add `mode`:
```java
public record SearchRequest(String query, String repo, String domain, String type, Integer k, String mode) {}
```

- [ ] **Step 3: Compile + run tests**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/ \
        src/main/java/com/acme/airetrieval/api/SearchController.java \
        src/main/java/com/acme/airetrieval/api/dto/SearchRequest.java
git commit -m "feat: RetrievalOrchestrator — single entry point for hybrid/bm25 search"
```

---

### Task 7: Incremental upsert with contentHash deduplication

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/IndexService.java`

- [ ] **Step 1: Add contentHash lookup to LuceneSearcher**

Add to `LuceneSearcher.java`:
```java
public Optional<String> getContentHash(String chunkId) throws IOException {
    TopDocs td = searcher.search(new TermQuery(new Term("id", chunkId)), 1);
    if (td.totalHits.value() == 0) return Optional.empty();
    Document doc = reader.storedFields().document(td.scoreDocs[0].doc);
    return Optional.ofNullable(doc.get("content_hash"));
}
```

Add import:
```java
import java.util.Optional;
```

- [ ] **Step 2: Update IndexService to embed and check contentHash**

Replace the indexing loop body in `indexIncremental`:
```java
// Updated indexIncremental inner loop body:
for (Chunk rawChunk : chunks) {
    // Check if contentHash changed — skip re-embedding if not
    Optional<String> existingHash = searcher.getContentHash(rawChunk.id());
    if (existingHash.isPresent() && existingHash.get().equals(rawChunk.contentHash())) {
        skipped++;
        continue;
    }
    // Embed the chunk
    float[] vector = embeddingModel.embed(rawChunk.text());
    Chunk embeddedChunk = new Chunk(
        rawChunk.id(), rawChunk.repo(), rawChunk.path(), rawChunk.domain(),
        rawChunk.type(), rawChunk.fqn(), rawChunk.title(), rawChunk.section(),
        rawChunk.symbols(), rawChunk.gitSha(), rawChunk.contentHash(),
        rawChunk.lang(), rawChunk.text(), vector
    );
    indexer.upsert(embeddedChunk);
    indexed++;
}
```

Inject `EmbeddingModel` and `LuceneSearcher` into `IndexService`:
```java
// Updated IndexService constructor:
private final EmbeddingModel embeddingModel;
private final LuceneSearcher searcher;

public IndexService(GitChangeDetector changeDetector, SourceClassifier classifier,
                    MarkdownParser markdownParser, DocumentParser documentParser,
                    LuceneIndexer indexer, EmbeddingModel embeddingModel,
                    LuceneSearcher searcher) {
    this.changeDetector  = changeDetector;
    this.classifier      = classifier;
    this.markdownParser  = markdownParser;
    this.documentParser  = documentParser;
    this.indexer         = indexer;
    this.embeddingModel  = embeddingModel;
    this.searcher        = searcher;
}
```

Add `@Service` annotation remains.

- [ ] **Step 3: Run all tests**

```bash
mvn test -q
```
Expected: All tests pass. (Integration test already uses a LuceneIndexer with real indexDir; verify that it still works with the updated IndexService.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/IndexService.java \
        src/main/java/com/acme/airetrieval/index/LuceneSearcher.java
git commit -m "feat: skip re-embedding when contentHash unchanged — incremental index dedup"
```

---

### Task 8: Golden evaluation harness

**Files:**
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/eval/GoldenSetEvalTest.java`
- Create: `ai-retrieval/src/test/resources/eval/golden-queries.json`

- [ ] **Step 1: Create golden queries file (seed with 5 queries — expand to 50+ later)**

```json
[
  {
    "query": "how to configure Spring Boot application properties",
    "expectedPaths": ["docs/configuration.md", "README.md"],
    "minRecallAt5": 1
  },
  {
    "query": "Kafka consumer group lag monitoring",
    "expectedPaths": ["docs/kafka.md"],
    "minRecallAt5": 1
  },
  {
    "query": "Maven dependency management",
    "expectedPaths": ["docs/build.md"],
    "minRecallAt5": 1
  }
]
```

Place at: `src/test/resources/eval/golden-queries.json`

- [ ] **Step 2: Create eval harness**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/eval/GoldenSetEvalTest.java
package com.acme.airetrieval.eval;

import com.acme.airetrieval.index.HybridSearch;
import com.acme.airetrieval.index.model.SearchHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.InputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Run with: mvn test -Dtest=GoldenSetEvalTest -Deval.enabled=true
 * Skip in CI by default (index may be empty).
 */
@SpringBootTest
@Tag("eval")
@EnabledIfSystemProperty(named = "eval.enabled", matches = "true")
class GoldenSetEvalTest {

    @Autowired HybridSearch hybridSearch;

    record GoldenQuery(String query, List<String> expectedPaths, int minRecallAt5) {}

    @Test
    void goldenSetRecall() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream json = getClass().getResourceAsStream("/eval/golden-queries.json");
        List<GoldenQuery> queries = mapper.readValue(json,
            mapper.getTypeFactory().constructCollectionType(List.class, GoldenQuery.class));

        int total = 0, passed = 0;
        for (GoldenQuery gq : queries) {
            List<SearchHit> hits = hybridSearch.search(gq.query(), null, 5);
            long matched = hits.stream()
                .filter(h -> gq.expectedPaths().stream().anyMatch(ep -> h.path().contains(ep)))
                .count();
            boolean ok = matched >= gq.minRecallAt5();
            System.out.printf("[%s] query=%s matched=%d/%d%n",
                ok ? "PASS" : "FAIL", gq.query(), matched, gq.minRecallAt5());
            total++;
            if (ok) passed++;
        }

        double recallRate = (double) passed / total;
        System.out.printf("Recall@5 = %.0f%% (%d/%d)%n", recallRate * 100, passed, total);
        assertThat(recallRate).as("Recall@5 must be >= 0.6 (baseline)").isGreaterThanOrEqualTo(0.6);
    }
}
```

- [ ] **Step 3: Run eval (skipped in CI, manual only)**

```bash
mvn test -Dtest=GoldenSetEvalTest -Deval.enabled=true -q
```
Expected: SKIP (eval.enabled not set) or PASS if index is populated.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/acme/airetrieval/eval/ src/test/resources/eval/
git commit -m "test: golden eval harness — Recall@5 baseline against golden-queries.json"
```

---

### Task 9: Full test suite pass + Phase 1 exit check

- [ ] **Step 1: Run full suite**

```bash
mvn test -q
```
Expected: All tests pass, BUILD SUCCESS.

- [ ] **Step 2: Verify startup with embedding model (if staged)**

```bash
mvn spring-boot:run &
sleep 8
curl -s -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"Spring Boot configuration","mode":"hybrid","k":5}' | python3 -m json.tool
kill %1
```
Expected: Server starts without errors. Returns `{"hits":[],"total":0}` (empty index).

- [ ] **Step 3: Final commit**

```bash
git add -u
git commit -m "feat: Phase 1 complete — ONNX embeddings, KNN index, hybrid RRF search, eval harness"
```

**Phase 1 exit criterion:** `mvn test` passes. Hybrid search available at `POST /api/v1/search`. Embedding model runs in-process. Proceed to `2026-06-15-phase2-code-graph-mcp.md`.
