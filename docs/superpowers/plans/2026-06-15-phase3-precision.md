# Kira Phase 3 — Precision + Token Cuts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an ONNX cross-encoder reranker, context compaction with a hard token budget, dedicated endpoint/Kafka/bean extractors, and an `answer_context` MCP tool. Result: measurably fewer tokens per answer at equal or better accuracy.

**Architecture:** `Reranker` scores query-document pairs with a cross-encoder ONNX model. `ContextCompactor` assembles the final context string within a `TokenBudget`. `ApiSpecParser` handles OpenAPI/AsyncAPI. Annotation-driven extractors (`EndpointExtractor`, `KafkaExtractor`, `BeanExtractor`) run inside `JavaSourceParser`. `RetrievalOrchestrator` gains a `hybridRerank()` method used by a new `answer_context` MCP tool.

**Prerequisite:** Phase 0 + 1 + 2 complete. All tests pass.

**Model download (run once):**
```bash
# Download ms-marco-MiniLM-L-6-v2 cross-encoder in ONNX format
python3 -c "
from huggingface_hub import snapshot_download
snapshot_download(repo_id='cross-encoder/ms-marco-MiniLM-L-6-v2',
                  local_dir='/tmp/reranker',
                  allow_patterns=['onnx/model.onnx','tokenizer.json'])
"
cp /tmp/reranker/onnx/model.onnx ~/.kira/data/models/reranker.onnx
cp /tmp/reranker/tokenizer.json ~/.kira/data/models/reranker-tokenizer.json
```

**Tech Stack:** Phase 2 stack + swagger-parser 2.1.x for OpenAPI, asyncapi-parser or plain YAML for AsyncAPI

---

## File Map

```
ai-retrieval/src/main/java/com/acme/airetrieval/
├── embed/
│   └── Reranker.java                     (new — ONNX cross-encoder, scores query-doc pairs)
├── retrieve/
│   ├── RetrievalOrchestrator.java         (modify — add hybridRerank() method)
│   ├── ContextCompactor.java              (new — assembles compact context string)
│   └── TokenBudget.java                  (new — estimates token count, enforces cap)
├── ingest/
│   ├── parser/
│   │   ├── JavaSourceParser.java          (modify — add EndpointExtractor, KafkaExtractor, BeanExtractor logic)
│   │   └── ApiSpecParser.java             (new — OpenAPI/AsyncAPI → Chunks + GraphEvents)
│   └── model/
│       └── EndpointInfo.java              (new — record for REST endpoint metadata)
├── mcp/
│   └── McpTools.java                      (modify — add answer_context tool)
└── config/
    └── ApplicationProps.java              (modify — add reranker.model-path + token-budget)

src/test/java/com/acme/airetrieval/
├── embed/RerankerTest.java                (new)
├── retrieve/ContextCompactorTest.java     (new)
├── retrieve/TokenBudgetTest.java          (new)
└── ingest/parser/ApiSpecParserTest.java   (new)
```

---

### Task 1: ApplicationProps — add reranker + token budget config

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/config/ApplicationProps.java`
- Modify: `ai-retrieval/src/main/resources/application.yml`

- [ ] **Step 1: Update ApplicationProps**

```java
// Updated ApplicationProps.java
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
    Executor executor
) {
    public record Executor(int indexThreads) {}
    public record Embedding(Path modelPath, Path tokenizerPath, int dim) {}
    public record Reranker(Path modelPath, Path tokenizerPath, boolean enabled) {}
    public record TokenBudgetConfig(int defaultBudgetTokens, int charsPerToken) {}
}
```

- [ ] **Step 2: Update application.yml**

```yaml
kira:
  # ... existing config ...
  reranker:
    model-path: ${kira.models-dir}/reranker.onnx
    tokenizer-path: ${kira.models-dir}/reranker-tokenizer.json
    enabled: true
  token-budget:
    default-budget-tokens: 6000
    chars-per-token: 4
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
git commit -m "feat: extend ApplicationProps — reranker model path + token budget config"
```

---

### Task 2: TokenBudget

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/TokenBudget.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/retrieve/TokenBudgetTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/retrieve/TokenBudgetTest.java
package com.acme.airetrieval.retrieve;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TokenBudgetTest {

    private final TokenBudget budget = new TokenBudget(4); // 4 chars per token

    @Test
    void estimate_returnsCharCountDividedByCharsPerToken() {
        assertThat(budget.estimate("1234")).isEqualTo(1);
        assertThat(budget.estimate("12345678")).isEqualTo(2);
    }

    @Test
    void estimate_emptyString_returnsZero() {
        assertThat(budget.estimate("")).isEqualTo(0);
    }

    @Test
    void fits_withinBudget_returnsTrue() {
        assertThat(budget.fits("abcd", 1)).isTrue();
        assertThat(budget.fits("abcde", 1)).isFalse();
    }

    @Test
    void truncateToFit_longText_truncatesAtTokenBoundary() {
        String text = "word1 word2 word3 word4 word5";
        String result = budget.truncateToFit(text, 5); // 5 tokens = 20 chars
        assertThat(result.length()).isLessThanOrEqualTo(20);
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=TokenBudgetTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement TokenBudget**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/TokenBudget.java
package com.acme.airetrieval.retrieve;

public final class TokenBudget {

    private final int charsPerToken;

    public TokenBudget(int charsPerToken) {
        this.charsPerToken = charsPerToken;
    }

    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / charsPerToken);
    }

    public boolean fits(String text, int budgetTokens) {
        return estimate(text) <= budgetTokens;
    }

    public String truncateToFit(String text, int budgetTokens) {
        int maxChars = budgetTokens * charsPerToken;
        if (text.length() <= maxChars) return text;
        int boundary = text.lastIndexOf(' ', maxChars);
        return boundary > 0 ? text.substring(0, boundary) : text.substring(0, maxChars);
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
mvn test -Dtest=TokenBudgetTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/TokenBudget.java \
        src/test/java/com/acme/airetrieval/retrieve/TokenBudgetTest.java
git commit -m "feat: TokenBudget — char-based token estimation and truncation"
```

---

### Task 3: Reranker (ONNX cross-encoder)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/embed/Reranker.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/embed/RerankerTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/embed/RerankerTest.java
package com.acme.airetrieval.embed;

import org.junit.jupiter.api.*;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RerankerTest {

    static Reranker reranker;

    @BeforeAll
    static void setUp() throws Exception {
        Path model     = Path.of(System.getProperty("user.home"), ".kira/data/models/reranker.onnx");
        Path tokenizer = Path.of(System.getProperty("user.home"), ".kira/data/models/reranker-tokenizer.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(model.toFile().exists(),
            "reranker.onnx not found — run model download script first");
        reranker = new Reranker(model, tokenizer);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (reranker != null) reranker.close();
    }

    @Test
    @Order(1)
    void score_relevantDocHigherThanIrrelevant() throws Exception {
        String query = "Kafka consumer group configuration";
        String relevantDoc = "Configure the Kafka consumer group by setting group.id in application.yml";
        String irrelevantDoc = "Install Maven and run mvn clean install to build the project";

        float relevantScore = reranker.score(query, relevantDoc);
        float irrelevantScore = reranker.score(query, irrelevantDoc);

        assertThat(relevantScore).isGreaterThan(irrelevantScore);
    }

    @Test
    @Order(2)
    void rerank_listOfDocs_returnsSortedByScore() throws Exception {
        String query = "payment settlement";
        var docs = java.util.List.of("Pay the invoice", "Settle payment via gateway", "Configure logging");

        var ranked = reranker.rerank(query, docs, 2);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0)).contains("payment").or().contains("Settle");
    }
}
```

- [ ] **Step 2: Run test — verify it fails or skips**

```bash
mvn test -Dtest=RerankerTest -q 2>&1 | tail -5
```
Expected: FAIL or SKIP (if model not staged).

- [ ] **Step 3: Implement Reranker**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/embed/Reranker.java
package com.acme.airetrieval.embed;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;

import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;

public final class Reranker implements AutoCloseable {

    private static final int MAX_TOKENS = 512;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public Reranker(Path modelPath, Path tokenizerPath) throws OrtException, IOException {
        this.env = OrtEnvironment.getEnvironment();
        var opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath.toString(), opts);
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    }

    public float score(String query, String document) throws OrtException {
        // Cross-encoder: encode query+document pair together
        Encoding enc = tokenizer.encode(query, document, true); // truncate
        long[] ids  = enc.getIds();
        long[] mask = enc.getAttentionMask();
        long[] typeIds = enc.getTypeIds();

        if (ids.length > MAX_TOKENS) {
            ids     = Arrays.copyOf(ids, MAX_TOKENS);
            mask    = Arrays.copyOf(mask, MAX_TOKENS);
            typeIds = Arrays.copyOf(typeIds, MAX_TOKENS);
        }

        long[] shape = {1L, ids.length};

        try (var inputIds  = OnnxTensor.createTensor(env, LongBuffer.wrap(ids),     shape);
             var attnMask  = OnnxTensor.createTensor(env, LongBuffer.wrap(mask),    shape);
             var tokenType = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape);
             var result    = session.run(Map.of(
                 "input_ids",      inputIds,
                 "attention_mask", attnMask,
                 "token_type_ids", tokenType))) {

            // Logits output shape: [1, 1] or [1, 2] depending on model
            float[][] logits = (float[][]) result.get(0).getValue();
            // For binary relevance model: logits[0][0] is the relevance score
            return logits[0].length == 1 ? logits[0][0] : sigmoid(logits[0][1] - logits[0][0]);
        }
    }

    public List<String> rerank(String query, List<String> documents, int topN) throws OrtException {
        float[] scores = new float[documents.size()];
        for (int i = 0; i < documents.size(); i++) {
            scores[i] = score(query, documents.get(i));
        }

        return IntStream.range(0, documents.size())
            .boxed()
            .sorted((a, b) -> Float.compare(scores[b], scores[a]))
            .limit(topN)
            .map(documents::get)
            .toList();
    }

    private static float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
```

- [ ] **Step 4: Create OnnxConfig bean for Reranker**

Add to `OnnxConfig.java`:
```java
@Bean(destroyMethod = "close")
public Reranker reranker(ApplicationProps props) throws Exception {
    var r = props.reranker();
    if (!r.enabled()) return null;
    log.info("Loading ONNX reranker from {}", r.modelPath());
    return new Reranker(r.modelPath(), r.tokenizerPath());
}
```

- [ ] **Step 5: Run test — verify it passes (if model staged)**

```bash
mvn test -Dtest=RerankerTest -q
```
Expected: Tests run: 2, Failures: 0 (or SKIP if model absent).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/embed/Reranker.java \
        src/main/java/com/acme/airetrieval/config/OnnxConfig.java \
        src/test/java/com/acme/airetrieval/embed/RerankerTest.java
git commit -m "feat: Reranker — ONNX cross-encoder scores query-doc pairs for precision boost"
```

---

### Task 4: ContextCompactor

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java
package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ContextCompactorTest {

    private final TokenBudget budget = new TokenBudget(4);
    private final ContextCompactor compactor = new ContextCompactor(budget);

    private SearchHit codeHit(String id, String fqn, String text) {
        return new SearchHit(id, "repo", "src/" + id + ".java", Domain.CODE, "METHOD",
            fqn, null, null, text, 0.9f);
    }

    private SearchHit docHit(String id, String title, String section, String text) {
        return new SearchHit(id, "repo", "docs/" + id + ".md", Domain.KNOWLEDGE, "MD_SECTION",
            null, title, section, text, 0.8f);
    }

    @Test
    void compact_codeHit_includesSignatureAndSnippet() {
        var hit = codeHit("PaymentService", "com.acme.PaymentService#settle(String)",
            "public void settle(String orderId) { ... }");
        String result = compactor.compact(List.of(hit), 200);

        assertThat(result).contains("com.acme.PaymentService#settle(String)");
        assertThat(result).contains("settle");
    }

    @Test
    void compact_docHit_includesTitleAndSection() {
        var hit = docHit("kafka-guide", "Kafka Guide", "Consumer Configuration",
            "Set group.id to your service name.");
        String result = compactor.compact(List.of(hit), 200);

        assertThat(result).contains("Kafka Guide");
        assertThat(result).contains("Consumer Configuration");
    }

    @Test
    void compact_exceedsBudget_truncatesEarlyHits() {
        var hits = List.of(
            codeHit("A", "A#a()", "a".repeat(400)), // 100 tokens
            codeHit("B", "B#b()", "b".repeat(400))  // 100 tokens
        );
        String result = compactor.compact(hits, 50); // tight budget

        assertThat(result.length()).isLessThanOrEqualTo(50 * 4 + 100); // budget chars + overhead
    }

    @Test
    void compact_emptyHits_returnsEmptyString() {
        assertThat(compactor.compact(List.of(), 100)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=ContextCompactorTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement ContextCompactor**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java
package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Domain;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContextCompactor {

    private final TokenBudget budget;

    public ContextCompactor(TokenBudget budget) {
        this.budget = budget;
    }

    public String compact(List<SearchHit> hits, int budgetTokens) {
        if (hits.isEmpty()) return "";

        var sb = new StringBuilder();
        int used = 0;

        for (SearchHit hit : hits) {
            String block = buildBlock(hit);
            int cost = budget.estimate(block);

            if (used + cost > budgetTokens && sb.length() > 0) {
                // Try truncated version
                int remaining = budgetTokens - used;
                if (remaining <= 0) break;
                String truncated = budget.truncateToFit(block, remaining);
                sb.append(truncated).append("\n\n");
                break;
            }

            sb.append(block).append("\n\n");
            used += cost;
        }

        return sb.toString().stripTrailing();
    }

    private String buildBlock(SearchHit hit) {
        if (hit.domain() == Domain.CODE) {
            return buildCodeBlock(hit);
        } else {
            return buildDocBlock(hit);
        }
    }

    private String buildCodeBlock(SearchHit hit) {
        var sb = new StringBuilder();
        if (hit.fqn() != null) sb.append("// ").append(hit.fqn()).append('\n');
        if (hit.snippet() != null) sb.append(hit.snippet());
        return sb.toString();
    }

    private String buildDocBlock(SearchHit hit) {
        var sb = new StringBuilder();
        if (hit.title() != null) {
            sb.append("## ").append(hit.title());
            if (hit.section() != null) sb.append(" › ").append(hit.section());
            sb.append('\n');
        }
        if (hit.snippet() != null) sb.append(hit.snippet());
        return sb.toString();
    }
}
```

- [ ] **Step 4: Register TokenBudget as a bean**

Add to `LuceneConfig.java` (or create a `RetrievalConfig.java`):
```java
@Bean
public TokenBudget tokenBudget(ApplicationProps props) {
    return new TokenBudget(props.tokenBudget().charsPerToken());
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
mvn test -Dtest=ContextCompactorTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/ContextCompactor.java \
        src/main/java/com/acme/airetrieval/retrieve/TokenBudget.java \
        src/test/java/com/acme/airetrieval/retrieve/ContextCompactorTest.java
git commit -m "feat: ContextCompactor — assembles token-budgeted context from ranked hits"
```

---

### Task 5: RetrievalOrchestrator — add hybridRerank()

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`

- [ ] **Step 1: Update RetrievalOrchestrator**

```java
// Updated RetrievalOrchestrator.java
@Service
public class RetrievalOrchestrator {

    private static final int CANDIDATE_K = 50;

    private final HybridSearch hybridSearch;
    private final Reranker reranker;     // nullable if disabled
    private final ContextCompactor compactor;
    private final ApplicationProps props;

    public RetrievalOrchestrator(LuceneSearcher searcher, EmbeddingModel embedding,
                                  @Autowired(required = false) Reranker reranker,
                                  ContextCompactor compactor, ApplicationProps props) {
        this.hybridSearch = new HybridSearch(searcher, embedding);
        this.reranker     = reranker;
        this.compactor    = compactor;
        this.props        = props;
    }

    public List<SearchHit> hybrid(String query, SearchFilter filter, int k)
            throws IOException, OrtException {
        return hybridSearch.search(query, filter, k);
    }

    public List<SearchHit> bm25Only(String query, SearchFilter filter, int k) throws IOException {
        return hybridSearch.bm25Only(query, filter, k);
    }

    /**
     * Retrieve CANDIDATE_K candidates, rerank with cross-encoder, return top k.
     */
    public List<SearchHit> hybridRerank(String query, SearchFilter filter, int k)
            throws IOException, OrtException {
        List<SearchHit> candidates = hybridSearch.search(query, filter, CANDIDATE_K);
        if (reranker == null || candidates.isEmpty()) {
            return candidates.stream().limit(k).toList();
        }

        // Score each candidate
        List<String> texts = candidates.stream()
            .map(h -> h.snippet() != null ? h.snippet() : "")
            .toList();

        List<String> reranked = reranker.rerank(query, texts, k);

        // Map back to original SearchHit objects by snippet
        return reranked.stream()
            .map(text -> candidates.stream()
                .filter(h -> text.equals(h.snippet()))
                .findFirst()
                .orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    public String answerContext(String query, SearchFilter filter, int budgetTokens)
            throws IOException, OrtException {
        int k = props.defaultSearchK();
        List<SearchHit> hits = hybridRerank(query, filter, k);
        return compactor.compact(hits, budgetTokens);
    }
}
```

Add import:
```java
import org.springframework.beans.factory.annotation.Autowired;
```

- [ ] **Step 2: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java
git commit -m "feat: RetrievalOrchestrator — hybridRerank() uses cross-encoder + answerContext() compacts with budget"
```

---

### Task 6: ApiSpecParser (OpenAPI/AsyncAPI)

**Files:**
- Modify: `ai-retrieval/pom.xml` (add swagger-parser dependency)
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java`
- Create: `ai-retrieval/src/test/resources/fixtures/petstore-mini.yaml`

- [ ] **Step 1: Add swagger-parser dependency**

Add to pom.xml:
```xml
<dependency>
  <groupId>io.swagger.parser.v3</groupId>
  <artifactId>swagger-parser</artifactId>
  <version>2.1.22</version>
</dependency>
```

- [ ] **Step 2: Create test fixture**

Create `src/test/resources/fixtures/petstore-mini.yaml`:
```yaml
openapi: "3.0.3"
info:
  title: Petstore Mini
  version: "1.0"
paths:
  /pets:
    get:
      operationId: listPets
      summary: List all pets
      tags: [pets]
      responses:
        "200":
          description: A list of pets
  /pets/{petId}:
    get:
      operationId: getPet
      summary: Get a specific pet
      tags: [pets]
      parameters:
        - name: petId
          in: path
          required: true
          schema:
            type: string
      responses:
        "200":
          description: A pet
```

- [ ] **Step 3: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ApiSpecParserTest {

    private final ApiSpecParser parser = new ApiSpecParser();

    @Test
    void parse_openApiSpec_returnsChunkPerOperation() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/petstore-mini.yaml");
        var result = parser.parse("repo", "api/petstore.yaml", "sha1", fixture);

        List<Chunk> chunks = result.chunks();
        assertThat(chunks).hasSize(2);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.domain()).isEqualTo(Domain.KNOWLEDGE);
            assertThat(c.type()).isEqualTo("OPENAPI_OP");
        });
        assertThat(chunks).anySatisfy(c -> assertThat(c.fqn()).isEqualTo("GET /pets"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.fqn()).isEqualTo("GET /pets/{petId}"));
    }

    @Test
    void parse_openApiSpec_emitsEndpointNodes() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/petstore-mini.yaml");
        var result = parser.parse("repo", "api/petstore.yaml", "sha1", fixture);

        assertThat(result.events()).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.NodeEvent.class);
            var ne = (GraphEvent.NodeEvent) e;
            assertThat(ne.tags()).contains("ENDPOINT");
            assertThat(ne.id()).contains("GET /pets");
        });
    }
}
```

- [ ] **Step 4: Run test — verify it fails**

```bash
mvn test -Dtest=ApiSpecParserTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 5: Implement ApiSpecParser**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

import java.nio.file.Path;
import java.util.*;

public final class ApiSpecParser {

    public record ParseResult(List<Chunk> chunks, List<GraphEvent> events) {}

    public ParseResult parse(String repo, String specPath, String gitSha, Path file) {
        var options = new ParseOptions();
        options.setResolve(true);
        var result = new OpenAPIV3Parser().read(file.toString(), null, options);
        if (result == null || result.getPaths() == null) {
            return new ParseResult(List.of(), List.of());
        }

        List<Chunk> chunks = new ArrayList<>();
        List<GraphEvent> events = new ArrayList<>();

        for (Map.Entry<String, PathItem> pathEntry : result.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();

            processOperation("GET",    path, item.getGet(),    repo, specPath, gitSha, chunks, events);
            processOperation("POST",   path, item.getPost(),   repo, specPath, gitSha, chunks, events);
            processOperation("PUT",    path, item.getPut(),    repo, specPath, gitSha, chunks, events);
            processOperation("DELETE", path, item.getDelete(), repo, specPath, gitSha, chunks, events);
            processOperation("PATCH",  path, item.getPatch(),  repo, specPath, gitSha, chunks, events);
        }

        return new ParseResult(chunks, events);
    }

    private void processOperation(String method, String path, Operation op,
                                   String repo, String specPath, String gitSha,
                                   List<Chunk> chunks, List<GraphEvent> events) {
        if (op == null) return;
        String endpointId = method + " " + path;
        String text = buildText(method, path, op);

        events.add(new GraphEvent.NodeEvent(endpointId, "Endpoint",
            Set.of("ENDPOINT"), endpointId, op.getSummary()));

        chunks.add(new Chunk(
            specPath + "#" + endpointId.replace(" ", "-"), repo, specPath,
            Domain.KNOWLEDGE, "OPENAPI_OP", endpointId,
            op.getSummary(), null, extractSymbols(op),
            gitSha, MarkdownParser.hash(text), "openapi", text, null
        ));
    }

    private String buildText(String method, String path, Operation op) {
        var sb = new StringBuilder();
        sb.append(method).append(" ").append(path).append('\n');
        if (op.getSummary() != null) sb.append(op.getSummary()).append('\n');
        if (op.getDescription() != null) sb.append(op.getDescription()).append('\n');
        if (op.getOperationId() != null) sb.append("operationId: ").append(op.getOperationId()).append('\n');
        if (op.getTags() != null) sb.append("tags: ").append(op.getTags()).append('\n');
        return sb.toString();
    }

    private List<String> extractSymbols(Operation op) {
        List<String> symbols = new ArrayList<>();
        if (op.getOperationId() != null) symbols.add(op.getOperationId());
        if (op.getTags() != null) symbols.addAll(op.getTags());
        return symbols;
    }
}
```

- [ ] **Step 6: Wire ApiSpecParser into IndexService**

In `IndexService.java`, add `ApiSpecParser` injection and add case to `SourceClassifier`:

In `SourceClassifier.java`, add:
```java
private static final Set<String> API_SPEC_EXTS = Set.of("yaml", "yml", "json");
// In classify():
if (API_SPEC_EXTS.contains(ext) && path.contains("api")) return FileType.API_SPEC;
```

Add `API_SPEC` to the `FileType` enum.

In `IndexService.java` switch:
```java
case API_SPEC -> {
    var specResult = apiSpecParser.parse(repo, change.path(), toSha, fullPath);
    graphExtractor.apply(specResult.events());
    yield specResult.chunks();
}
```

Add `ApiSpecParser` bean to `LuceneConfig`:
```java
@Bean
public ApiSpecParser apiSpecParser() {
    return new ApiSpecParser();
}
```

- [ ] **Step 7: Run test — verify it passes**

```bash
mvn test -Dtest=ApiSpecParserTest -q
```
Expected: Tests run: 2, Failures: 0, Errors: 0.

- [ ] **Step 8: Run full suite**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 9: Commit**

```bash
git add pom.xml \
        src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java \
        src/main/java/com/acme/airetrieval/ingest/SourceClassifier.java \
        src/main/java/com/acme/airetrieval/ingest/IndexService.java \
        src/main/java/com/acme/airetrieval/config/LuceneConfig.java \
        src/test/java/com/acme/airetrieval/ingest/parser/ApiSpecParserTest.java \
        src/test/resources/fixtures/petstore-mini.yaml
git commit -m "feat: ApiSpecParser — OpenAPI ops → OPENAPI_OP chunks + ENDPOINT graph nodes"
```

---

### Task 7: answer_context MCP tool

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/mcp/McpTools.java`

- [ ] **Step 1: Add answer_context tool to McpTools**

Add to `McpTools.java`:
```java
@Tool(description = "Best single tool for answering questions. Retrieves the top candidates, " +
    "reranks with cross-encoder, compacts to a token budget, and returns the blended context " +
    "(code signatures + doc sections + graph links). Use this before trying multiple separate searches.")
public String answer_context(
        @ToolParam(description = "The question or query to answer") String query,
        @ToolParam(description = "Token budget for the response (default 6000)", required = false) Integer budgetTokens)
        throws Exception {
    int budget = budgetTokens != null ? budgetTokens : 6000;
    return retrieval.answerContext(query, null, budget);
}
```

- [ ] **Step 2: Run full suite**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/mcp/McpTools.java
git commit -m "feat: answer_context MCP tool — hybrid+rerank+compact in one call"
```

---

### Phase 3 Exit Criterion

Run:
```bash
mvn test -q && mvn spring-boot:run &
sleep 8
# Test answer_context REST endpoint (or via MCP client)
curl -s -X POST http://localhost:8094/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"payment settlement flow","mode":"hybrid","k":5}'
curl -s http://localhost:8094/actuator/health
kill %1
```

**All tests pass + reranker active + `answer_context` available in MCP = Phase 3 complete.**

Run golden eval:
```bash
mvn test -Dtest=GoldenSetEvalTest -Deval.enabled=true -q
```
Expected: Recall@5 >= 0.6 (or higher than Phase 1 baseline).

Proceed to `2026-06-15-phase4-scale.md`.
