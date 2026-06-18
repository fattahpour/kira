# MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the existing `McpTools` methods into a working Spring AI MCP server reachable by Claude Code via both stdio and HTTP/SSE transport.

**Architecture:** Spring AI 1.0.0 MCP server requires an explicit `ToolCallbackProvider` bean — `@Tool` annotated methods are NOT auto-registered without one. `MethodToolCallbackProvider.builder().toolObjects(bean).build()` wraps the bean. Two transports are configured: stdio (for Claude Code CLI process spawning) and HTTP/SSE (for `http://localhost:8094/sse` Claude Code remote connection). Two new tools are added: `index_status` (Lucene doc count) and `expand_context` (multi-hop graph BFS).

**Tech Stack:** Spring AI 1.0.0 (`spring-ai-starter-mcp-server-webmvc`), `MethodToolCallbackProvider`, `ToolCallbackProvider`, Apache Lucene 10 `IndexReader.numDocs()`, JUnit 5, Mockito.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `src/main/java/com/acme/airetrieval/config/McpConfig.java` | Create | `ToolCallbackProvider` bean that registers McpTools |
| `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java` | Modify | Add `numDocs()` |
| `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java` | Modify | Add `indexDocCount()` delegate |
| `src/main/java/com/acme/airetrieval/retrieve/dto/IndexStatus.java` | Create | Return type for `index_status` tool |
| `src/main/java/com/acme/airetrieval/retrieve/dto/ExpandedContext.java` | Create | Return type for `expand_context` tool |
| `src/main/java/com/acme/airetrieval/graph/GraphQueries.java` | Modify | Add `expandContext(List<String>, int)` |
| `src/main/java/com/acme/airetrieval/mcp/McpTools.java` | Modify | Add `index_status`, `expand_context` tools |
| `pom.xml` | Modify | Swap to `spring-ai-starter-mcp-server-webmvc` |
| `src/main/resources/application.yml` | Modify | Add HTTP transport config |
| `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java` | Create | Unit tests for all McpTools methods |
| `.mcp.json` | Create | Claude Code MCP connection config |

---

### Task 1: Tool registration — McpConfig.java

Without this task, the MCP server starts but exposes zero tools. Spring AI 1.0.0 requires an explicit `ToolCallbackProvider` bean. The `@Component` on `McpTools` makes it a Spring bean, but the MCP autoconfiguration only looks for `ToolCallbackProvider` beans when registering tools.

**Files:**

- Create: `src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java`
- Create: `src/main/java/com/acme/airetrieval/config/McpConfig.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java
package com.acme.airetrieval.mcp;

import com.acme.airetrieval.AiRetrievalApplication;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AiRetrievalApplication.class, properties = {
    "kira.index-dir=${java.io.tmpdir}/kira-mcp-reg-test",
    "kira.embedding.dim=32",
    "spring.ai.mcp.server.stdio=false"
})
class McpToolsRegistrationTest {

    @Autowired
    ToolCallbackProvider toolCallbackProvider;

    @Test
    void toolProvider_exposesAllMcpTools() {
        var names = java.util.Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .map(tc -> tc.getToolDefinition().name())
            .toList();
        assertThat(names).contains(
            "search_code", "search_knowledge", "semantic_search", "answer_context",
            "get_symbol", "get_callers", "get_callees", "get_kafka_flow"
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd <kira-install-dir>
mvn test -pl . -Dtest=McpToolsRegistrationTest -q 2>&1 | grep -E "FAIL|ERROR|NoSuchBean|expected"
```

Expected: FAIL — `NoSuchBeanDefinitionException: ToolCallbackProvider` or `AssertionError` (names list empty).

- [ ] **Step 3: Create McpConfig.java**

```java
// src/main/java/com/acme/airetrieval/config/McpConfig.java
package com.acme.airetrieval.config;

import com.acme.airetrieval.mcp.McpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(McpTools tools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -pl . -Dtest=McpToolsRegistrationTest -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected:
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/McpConfig.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java
git commit -m "feat(mcp): register McpTools via ToolCallbackProvider"
```

---

### Task 2: `index_status` tool — NrtLuceneSearcher.numDocs() + IndexStatus DTO

The `index_status` tool lets Claude Code ask "how many docs are indexed?" before deciding whether to search or trigger a re-index.

**Files:**

- Modify: `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java` — add `numDocs()`
- Modify: `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java` — add `indexDocCount()`
- Create: `src/main/java/com/acme/airetrieval/retrieve/dto/IndexStatus.java`
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java` — add `index_status()`
- Test: `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
package com.acme.airetrieval.mcp;

import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.retrieve.RetrievalOrchestrator;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolsTest {

    RetrievalOrchestrator retrieval;
    GraphQueries graph;
    McpTools tools;

    @BeforeEach
    void setup() {
        retrieval = mock(RetrievalOrchestrator.class);
        graph = mock(GraphQueries.class);
        tools = new McpTools(retrieval, graph);
    }

    @Test
    void index_status_returns_doc_count() throws Exception {
        when(retrieval.indexDocCount()).thenReturn(42);
        IndexStatus status = tools.index_status();
        assertThat(status.totalDocs()).isEqualTo(42);
        assertThat(status.serverVersion()).isNotBlank();
    }

    @Test
    void search_code_delegates_to_retrieval() throws Exception {
        when(retrieval.hybrid(eq("foo"), any(SearchFilter.class), eq(5))).thenReturn(List.of());
        var result = tools.search_code("foo", null, 5);
        assertThat(result).isNotNull();
        verify(retrieval).hybrid(eq("foo"), any(SearchFilter.class), eq(5));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=McpToolsTest -q 2>&1 | grep -E "FAIL|ERROR|cannot find"
```

Expected: compile error — `indexDocCount()` not defined, `IndexStatus` not defined.

- [ ] **Step 3: Create IndexStatus DTO**

```java
// src/main/java/com/acme/airetrieval/retrieve/dto/IndexStatus.java
package com.acme.airetrieval.retrieve.dto;

public record IndexStatus(int totalDocs, String serverVersion) {}
```

- [ ] **Step 4: Add numDocs() to NrtLuceneSearcher**

In `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`, add after the `maybeReopen()` method (after line 36):

```java
    public int numDocs() throws IOException {
        IndexSearcher searcher = manager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            manager.release(searcher);
        }
    }
```

- [ ] **Step 5: Add indexDocCount() to RetrievalOrchestrator**

In `src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java`:

Add field after line 16:
```java
    private final NrtLuceneSearcher searcher;
```

Update constructor — add `searcher` assignment after line 24:
```java
        this.searcher = searcher;
```

Add method before the final `ScoredHit` record (before the last line `private record ScoredHit...`):
```java
    public int indexDocCount() throws IOException {
        return searcher.numDocs();
    }
```

The full updated `RetrievalOrchestrator.java`:

```java
package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.embed.Reranker;
import com.acme.airetrieval.index.HybridSearch;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.List;

public class RetrievalOrchestrator {
    private static final int CANDIDATE_K = 50;
    private final HybridSearch hybridSearch;
    private final NrtLuceneSearcher searcher;
    private final Reranker reranker;
    private final ContextCompactor compactor;
    private final ApplicationProps props;

    public RetrievalOrchestrator(NrtLuceneSearcher searcher, EmbeddingModel embeddingModel,
                                 ObjectProvider<Reranker> reranker, ContextCompactor compactor,
                                 ApplicationProps props) {
        this.hybridSearch = new HybridSearch(searcher, embeddingModel);
        this.searcher = searcher;
        this.reranker = reranker.getIfAvailable();
        this.compactor = compactor;
        this.props = props;
    }

    public List<SearchHit> hybrid(String query, SearchFilter filter, int k) throws Exception {
        return hybridSearch.search(query, filter, k);
    }

    public List<SearchHit> bm25Only(String query, SearchFilter filter, int k) throws Exception {
        return hybridSearch.bm25Only(query, filter, k);
    }

    public List<SearchHit> hybridRerank(String query, SearchFilter filter, int k) throws Exception {
        List<SearchHit> candidates = hybridSearch.search(query, filter, Math.max(k, CANDIDATE_K));
        if (reranker == null || candidates.isEmpty()) return candidates.stream().limit(k).toList();
        List<ScoredHit> scored = new java.util.ArrayList<>();
        for (SearchHit hit : candidates) {
            scored.add(new ScoredHit(hit, reranker.score(query, hit.snippet())));
        }
        return scored.stream()
            .sorted((a, b) -> Float.compare(b.score, a.score))
            .limit(k)
            .map(ScoredHit::hit)
            .toList();
    }

    public String answerContext(String query, SearchFilter filter, int budgetTokens) throws Exception {
        int budget = budgetTokens <= 0 ? props.tokenBudget().defaultBudgetTokens() : budgetTokens;
        return compactor.compact(hybridRerank(query, filter, props.defaultSearchK()), budget);
    }

    public int indexDocCount() throws IOException {
        return searcher.numDocs();
    }

    private record ScoredHit(SearchHit hit, float score) {}
}
```

- [ ] **Step 6: Add index_status() to McpTools**

In `src/main/java/com/acme/airetrieval/mcp/McpTools.java`, add at the end (before the closing `}`):

```java
    @Tool(description = "Return number of indexed documents and server version")
    public IndexStatus index_status() throws Exception {
        return new IndexStatus(retrieval.indexDocCount(), "0.1.0");
    }
```

Also add the import at the top:
```java
import com.acme.airetrieval.retrieve.dto.IndexStatus;
```

- [ ] **Step 7: Run tests**

```bash
mvn test -pl . -Dtest=McpToolsTest -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected:
```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java \
        src/main/java/com/acme/airetrieval/retrieve/RetrievalOrchestrator.java \
        src/main/java/com/acme/airetrieval/retrieve/dto/IndexStatus.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java
git commit -m "feat(mcp): add index_status tool"
```

---

### Task 3: `expand_context` tool — GraphQueries.expandContext() + ExpandedContext DTO

`expand_context` lets Claude Code widen the search: "given these FQNs, show me all related symbols within N hops." The BFS visits both in-edges (callers) and out-edges (callees) from each seed, collecting signatures.

**Files:**

- Create: `src/main/java/com/acme/airetrieval/retrieve/dto/ExpandedContext.java`
- Modify: `src/main/java/com/acme/airetrieval/graph/GraphQueries.java` — add `expandContext()`
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java` — add `expand_context()`
- Test: `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java` — add expandContext test

- [ ] **Step 1: Write the failing test**

In `src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`, add a new test (keep existing tests, add this at the end):

```java
    @Test
    void expandContext_returns_neighbor_signatures() {
        // build a small graph: A -> B -> C
        CodeGraphStore store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("A", "classA", List.of(), "void A()", null));
        store.applyEvent(new GraphEvent.NodeEvent("B", "classB", List.of(), "void B()", null));
        store.applyEvent(new GraphEvent.NodeEvent("C", "classC", List.of(), "void C()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("A", "B", GraphEdge.EdgeType.CALLS));
        store.applyEvent(new GraphEvent.EdgeEvent("B", "C", GraphEdge.EdgeType.CALLS));
        GraphQueries q = new GraphQueries(store);

        // 1 hop from A: should reach B (not C)
        List<String> hop1 = q.expandContext(List.of("A"), 1);
        assertThat(hop1).contains("void B()");
        assertThat(hop1).doesNotContain("void C()");

        // 2 hops from A: should reach B and C
        List<String> hop2 = q.expandContext(List.of("A"), 2);
        assertThat(hop2).contains("void B()", "void C()");
    }
```

You also need these imports at the top of `GraphQueriesTest.java` if not already present:
```java
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.graph.model.GraphEdge;
import java.util.List;
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -pl . -Dtest=GraphQueriesTest#expandContext_returns_neighbor_signatures -q 2>&1 | grep -E "FAIL|ERROR|cannot find"
```

Expected: compile error — `expandContext` not defined on `GraphQueries`.

- [ ] **Step 3: Create ExpandedContext DTO**

```java
// src/main/java/com/acme/airetrieval/retrieve/dto/ExpandedContext.java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record ExpandedContext(List<String> seedFqns, int hops, List<String> signatures) {}
```

- [ ] **Step 4: Add expandContext() to GraphQueries**

In `src/main/java/com/acme/airetrieval/graph/GraphQueries.java`, add these imports at the top if missing:

```java
import java.util.LinkedHashSet;
import java.util.Objects;
```

Add this method after `getKafkaFlow()` (before the closing `}`):

```java
    public List<String> expandContext(List<String> seedFqns, int hops) {
        Set<String> visited = new LinkedHashSet<>(seedFqns);
        Set<String> frontier = new LinkedHashSet<>(seedFqns);
        for (int h = 0; h < hops; h++) {
            Set<String> next = new LinkedHashSet<>();
            for (String fqn : frontier) {
                store.getOutEdges(fqn).forEach(e -> next.add(e.to()));
                store.getInEdges(fqn).forEach(e -> next.add(e.from()));
            }
            next.removeAll(visited);
            visited.addAll(next);
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return visited.stream()
            .map(id -> store.getNode(id).map(GraphNode::signature).orElse(null))
            .filter(Objects::nonNull)
            .toList();
    }
```

- [ ] **Step 5: Run test to verify it passes**

```bash
mvn test -pl . -Dtest=GraphQueriesTest -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected:
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 6: Add expand_context() to McpTools**

In `src/main/java/com/acme/airetrieval/mcp/McpTools.java`:

Add import:
```java
import com.acme.airetrieval.retrieve.dto.ExpandedContext;
import java.util.Arrays;
```

Add method before the closing `}`:

```java
    @Tool(description = "Expand context by walking the code graph from seed FQNs up to N hops, returning related symbol signatures")
    public ExpandedContext expand_context(
        @ToolParam(description = "comma-separated fully qualified names to start from") String fqns,
        @ToolParam(description = "number of graph hops, 1 = direct neighbors only") int hops) {
        List<String> seeds = Arrays.stream(fqns.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> signatures = graph.expandContext(seeds, hops);
        return new ExpandedContext(seeds, hops, signatures);
    }
```

Note: `fqns` is a comma-separated string instead of `String[]` because MCP tool parameters must be JSON-serializable primitives or simple objects. Arrays work via JSON but a comma-separated string is simpler for LLM callers.

- [ ] **Step 7: Add expand_context test to McpToolsTest**

In `src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java`, add:

```java
    @Test
    void expand_context_delegates_to_graph() {
        when(graph.expandContext(List.of("com.acme.Foo", "com.acme.Bar"), 2))
            .thenReturn(List.of("void baz()"));
        var result = tools.expand_context("com.acme.Foo, com.acme.Bar", 2);
        assertThat(result.signatures()).containsExactly("void baz()");
        assertThat(result.hops()).isEqualTo(2);
        assertThat(result.seedFqns()).containsExactly("com.acme.Foo", "com.acme.Bar");
    }
```

Also add to the `McpToolsRegistrationTest.toolProvider_exposesAllMcpTools()` assertion:

```java
        assertThat(names).contains(
            "search_code", "search_knowledge", "semantic_search", "answer_context",
            "get_symbol", "get_callers", "get_callees", "get_kafka_flow",
            "expand_context", "index_status"
        );
```

- [ ] **Step 8: Run all tests**

```bash
mvn test -pl . -Dtest="McpToolsTest,GraphQueriesTest,McpToolsRegistrationTest" -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected:
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0   ← GraphQueriesTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0   ← McpToolsTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0   ← McpToolsRegistrationTest
BUILD SUCCESS
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/GraphQueries.java \
        src/main/java/com/acme/airetrieval/retrieve/dto/ExpandedContext.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java \
        src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsTest.java \
        src/test/java/com/acme/airetrieval/mcp/McpToolsRegistrationTest.java
git commit -m "feat(mcp): add expand_context tool and GraphQueries.expandContext()"
```

---

### Task 4: HTTP/SSE transport

Add `spring-ai-starter-mcp-server-webmvc` so the MCP server is reachable at `http://localhost:8094/sse` in addition to stdio. Both transports coexist: stdio is for Claude Code CLI process spawning; HTTP is for a running server.

**Files:**

- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Update pom.xml**

Replace the existing `spring-ai-starter-mcp-server` dependency with `spring-ai-starter-mcp-server-webmvc`:

Find:
```xml
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-server</artifactId></dependency>
```

Replace with:
```xml
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-starter-mcp-server-webmvc</artifactId></dependency>
```

- [ ] **Step 2: Update application.yml**

Replace the current `spring.ai.mcp.server` block:

```yaml
spring:
  application:
    name: kira
  ai:
    mcp:
      server:
        name: kira
        version: 0.1.0
        stdio: true
        type: SYNC
        sse-message-endpoint: /mcp/message
```

- [ ] **Step 3: Verify compile and tests still pass**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|BUILD"
```

Expected:
```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Note: The integration test already sets `spring.ai.mcp.server.stdio=false` which prevents stdio from blocking test startup.

- [ ] **Step 4: Smoke test HTTP endpoint**

Start the server in a separate terminal:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.embedding.model-path=/dev/null \
  --kira.reranker.enabled=false \
  --spring.ai.mcp.server.stdio=false \
  --server.port=18080
```

Then probe the SSE endpoint:

```bash
curl -s -H "Accept: text/event-stream" http://localhost:18080/sse &
sleep 1
curl -s http://localhost:18080/actuator/health
```

Expected from health: `{"status":"UP"}`

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "feat(mcp): add HTTP/SSE transport via spring-ai-starter-mcp-server-webmvc"
```

---

### Task 5: Claude Code `.mcp.json` config

This file tells Claude Code how to connect to Kira as an MCP server. Two connection methods are provided: process spawn (stdio) and HTTP.

**Files:**

- Create: `.mcp.json` (repo root)

- [ ] **Step 1: Create .mcp.json**

```json
{
  "mcpServers": {
    "kira-stdio": {
      "command": "java",
      "args": [
        "-jar",
        "<kira-install-dir>/target/ai-retrieval-0.1.0-SNAPSHOT.jar",
        "--spring.ai.mcp.server.stdio=true",
        "--spring.ai.mcp.server.type=SYNC",
        "--kira.reranker.enabled=false",
        "--server.port=0"
      ],
      "env": {}
    },
    "kira-http": {
      "url": "http://localhost:8094/sse"
    }
  }
}
```

`kira-stdio` spawns the JAR as a child process. `kira-http` connects to a running server. Use `kira-stdio` when no persistent server is running; use `kira-http` when Kira is running as a systemd service.

Note: `kira-stdio` requires ONNX model files to exist. If models aren't staged yet, the process will fail at startup. Stage models first:

```bash
# Download bge-small-en-v1.5 ONNX from HuggingFace:
# https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/main (or bge-small)
mkdir -p ~/.kira/data/models
# then place bge-small.onnx and tokenizer.json under ~/.kira/data/models/
```

- [ ] **Step 2: Verify Claude Code recognizes the config**

```bash
# From the kira directory, Claude Code will pick up .mcp.json automatically.
# Check by listing MCP servers (requires Claude Code CLI installed):
claude mcp list 2>/dev/null || echo "Claude Code CLI not in PATH — manually add .mcp.json to ~/.claude/claude_desktop_config.json"
```

- [ ] **Step 3: Commit**

```bash
git add .mcp.json
git commit -m "feat(mcp): add .mcp.json for Claude Code stdio and HTTP transport"
```

---

### Task 6: Full test run + build verification

- [ ] **Step 1: Run all tests**

```bash
mvn test -q 2>&1 | grep -E "Tests run:|FAIL|ERROR|BUILD"
```

Expected (at minimum 24 tests — 22 existing + McpToolsTest 3 + McpToolsRegistrationTest 1):

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- [ ] **Step 2: Package fat JAR**

```bash
mvn clean package -q -DskipTests
ls -lh target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

Expected: file exists, size > 50 MB.

- [ ] **Step 3: Verify stdio MCP startup**

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}' | \
  java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
    --spring.ai.mcp.server.stdio=true \
    --kira.reranker.enabled=false \
    --kira.embedding.model-path=/dev/null \
    --server.port=0 2>/dev/null | head -5
```

Expected: JSON-RPC response containing `"result":{"protocolVersion":"2024-11-05",...}`.

- [ ] **Step 4: Verify tools/list**

```bash
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
) | java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
    --spring.ai.mcp.server.stdio=true \
    --kira.reranker.enabled=false \
    --kira.embedding.model-path=/dev/null \
    --server.port=0 2>/dev/null | grep -o '"name":"[^"]*"'
```

Expected: all 10 tool names printed:
```
"name":"search_code"
"name":"search_knowledge"
"name":"semantic_search"
"name":"answer_context"
"name":"get_symbol"
"name":"get_callers"
"name":"get_callees"
"name":"get_kafka_flow"
"name":"expand_context"
"name":"index_status"
```

- [ ] **Step 5: Final commit**

```bash
git add .
git commit -m "chore(mcp): verify all 10 tools registered via MCP protocol"
```

---

## Self-Review

### Spec coverage

From CLAUDE.md MCP tools list:

| Tool | Covered |
|------|---------|
| `search_code` | Existing — Task 1 registers it |
| `search_knowledge` | Existing — Task 1 registers it |
| `semantic_search` | Existing — Task 1 registers it |
| `answer_context` | Existing — Task 1 registers it |
| `get_symbol(fqn)` | Existing — Task 1 registers it |
| `get_callers(fqn, depth)` | Existing — Task 1 registers it |
| `get_callees(fqn, depth)` | Existing — Task 1 registers it |
| `get_endpoint(method, path)` | Phase 3 — out of scope |
| `get_kafka_flow(topic)` | Existing — Task 1 registers it |
| `get_bean_graph(name)` | Phase 3 — out of scope |
| `get_design_for_symbol(fqn)` | Phase 3 — out of scope |
| `get_code_for_doc(docId)` | Phase 3 — out of scope |
| `check_spec_vs_impl` | Phase 3 — out of scope |
| `expand_context(seedFqns, hops)` | Task 3 |
| `index_status` | Task 2 |

### Placeholder scan

No TBDs, no "implement later", no "similar to Task N" — all code is complete.

### Type consistency

- `IndexStatus(int totalDocs, String serverVersion)` — defined in Task 2 Step 3, used in Task 2 Step 6 and McpToolsTest Step 1. ✓
- `ExpandedContext(List<String> seedFqns, int hops, List<String> signatures)` — defined in Task 3 Step 3, returned in Task 3 Step 6, asserted in Task 3 Step 7. ✓
- `GraphQueries.expandContext(List<String>, int)` — defined in Task 3 Step 4, tested in Task 3 Step 1, called in Task 3 Step 6. ✓
- `RetrievalOrchestrator.indexDocCount()` — defined in Task 2 Step 5, mocked in McpToolsTest Step 1, called in McpTools Step 6. ✓
- `McpTools.index_status()` returns `IndexStatus` — consistent throughout. ✓
- `McpTools.expand_context(String, int)` returns `ExpandedContext` — consistent throughout. ✓
