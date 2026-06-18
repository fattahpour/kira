# Kira Phase 2 — Code Graph + MCP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a deterministic code graph from JavaParser+SymbolSolver ASTs and expose it via Spring AI MCP server. An agent can ask "who calls X?", "consumers of topic T?", and "what does PaymentService do?" with single tool calls.

**Architecture:** `JavaSourceParser` emits `Chunk` records (method-level) plus graph events. `GraphExtractor` builds nodes/edges in a `CodeGraphStore` backed by JGraphT. `GraphQueries` answers structural questions. Spring AI MCP starter wires `McpTools` (@Tool methods) to stdio transport, so Claude Code and Codex can connect immediately.

**Prerequisite:** Phase 0 + Phase 1 complete. All tests pass.

**Tech Stack:** Phase 1 stack + JavaParser 3.26.2 with SymbolSolver, JGraphT 1.5.2, Spring AI 1.0.0 MCP server starter

---

## File Map

```
ai-retrieval/
├── pom.xml                                                  (add JavaParser + JGraphT + Spring AI deps)
├── src/main/java/com/acme/airetrieval/
│   ├── ingest/
│   │   └── parser/
│   │       └── JavaSourceParser.java                        (new — AST → Chunk + GraphEvent)
│   ├── graph/
│   │   ├── model/
│   │   │   ├── GraphNode.java                               (new — record: id, label, tags)
│   │   │   ├── GraphEdge.java                               (new — record: from, to, type)
│   │   │   └── GraphEvent.java                              (new — sealed: NodeEvent | EdgeEvent)
│   │   ├── CodeGraphStore.java                              (new — JGraphT in-memory graph)
│   │   ├── GraphExtractor.java                              (new — applies GraphEvents to store)
│   │   └── GraphQueries.java                                (new — callers/callees/symbol/kafka/bean)
│   ├── retrieve/
│   │   └── dto/
│   │       ├── SymbolView.java                              (new — fqn + signature + callers + callees)
│   │       └── KafkaFlow.java                               (new — topic + producers + consumers)
│   ├── mcp/
│   │   └── McpTools.java                                    (new — @Tool methods for MCP server)
│   ├── api/
│   │   └── GraphController.java                             (new — REST mirrors for graph tools)
│   └── ingest/
│       └── IndexService.java                                (modify — wire JavaSourceParser)
└── src/main/resources/
    └── application.yml                                      (add spring.ai.mcp.server config)
src/test/java/com/acme/airetrieval/
├── ingest/parser/JavaSourceParserTest.java                  (new)
├── graph/CodeGraphStoreTest.java                            (new)
└── graph/GraphQueriesTest.java                              (new)
```

---

### Task 1: Add JavaParser + JGraphT + Spring AI dependencies

**Files:**
- Modify: `ai-retrieval/pom.xml`
- Modify: `ai-retrieval/src/main/resources/application.yml`

- [ ] **Step 1: Add dependencies to pom.xml**

Add inside `<properties>`:
```xml
<spring-ai.version>1.0.0</spring-ai.version>
<jgrapht.version>1.5.2</jgrapht.version>
```

Add inside `<dependencies>`:
```xml
<!-- JavaParser + SymbolSolver -->
<dependency>
  <groupId>com.github.javaparser</groupId>
  <artifactId>javaparser-symbol-solver-core</artifactId>
  <version>${javaparser.version}</version>
</dependency>

<!-- JGraphT (embedded graph) -->
<dependency>
  <groupId>org.jgrapht</groupId>
  <artifactId>jgrapht-core</artifactId>
  <version>${jgrapht.version}</version>
</dependency>

<!-- Spring AI MCP Server -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
  <version>${spring-ai.version}</version>
</dependency>
```

Add Spring AI BOM inside `<dependencyManagement>`:
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

- [ ] **Step 2: Add Spring AI MCP config to application.yml**

```yaml
spring:
  ai:
    mcp:
      server:
        name: kira
        version: 0.1.0
        stdio: true
```

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/resources/application.yml
git commit -m "feat: add JavaParser, JGraphT, Spring AI MCP server starter dependencies"
```

---

### Task 2: Graph domain model

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/model/GraphNode.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/model/GraphEdge.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/model/GraphEvent.java`

- [ ] **Step 1: Create GraphNode**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/model/GraphNode.java
package com.acme.airetrieval.graph.model;

import java.util.Set;

public record GraphNode(
    String id,        // fully-qualified name: "com.acme.Foo" or "com.acme.Foo#bar(String)"
    String label,     // Class | Interface | Method | Endpoint | Topic | Bean | Repository
    Set<String> tags, // REST_CONTROLLER, KAFKA_CONSUMER, KAFKA_PRODUCER, REPOSITORY, BEAN, etc.
    String signature, // human-readable: "public void bar(String x)"
    String javadoc    // nullable
) {}
```

- [ ] **Step 2: Create GraphEdge**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/model/GraphEdge.java
package com.acme.airetrieval.graph.model;

public record GraphEdge(
    String from,
    String to,
    EdgeType type
) {
    public enum EdgeType {
        DECLARES,        // Class DECLARES Method
        CALLS,           // Method CALLS Method
        IMPLEMENTS,      // Class IMPLEMENTS Interface
        EXTENDS,         // Class EXTENDS Class
        EXPOSES,         // Controller EXPOSES Endpoint
        PRODUCES,        // Method PRODUCES Topic
        CONSUMES,        // Method CONSUMES Topic
        DEPENDS_ON,      // Bean DEPENDS_ON Bean
        SPECIFIES,       // OpenAPI op SPECIFIES Endpoint
        DESCRIBES,       // ADR DESCRIBES Class
        MENTIONS         // Doc MENTIONS Method/Class
    }
}
```

- [ ] **Step 3: Create GraphEvent (sealed hierarchy)**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/model/GraphEvent.java
package com.acme.airetrieval.graph.model;

import java.util.Set;

public sealed interface GraphEvent {
    record NodeEvent(String id, String label, Set<String> tags, String signature, String javadoc)
        implements GraphEvent {}
    record EdgeEvent(String from, String to, GraphEdge.EdgeType type)
        implements GraphEvent {}
    record TagEvent(String id, String tag)
        implements GraphEvent {}
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/model/
git commit -m "feat: graph domain model — GraphNode, GraphEdge, GraphEvent sealed hierarchy"
```

---

### Task 3: JavaSourceParser (AST → Chunks + GraphEvents)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class JavaSourceParserTest {

    private final JavaSourceParser parser = new JavaSourceParser();

    private static final String SIMPLE_CLASS = """
        package com.acme;

        import org.springframework.stereotype.Service;

        /** Processes payments. */
        @Service
        public class PaymentService {
            private final OrderService orderService;

            public PaymentService(OrderService orderService) {
                this.orderService = orderService;
            }

            /** Settles payment for an order. */
            public void settle(String orderId) {
                orderService.markPaid(orderId);
            }
        }
        """;

    @Test
    void parse_javaClass_returnsMethodChunk() {
        var result = parser.parse("repo", "src/PaymentService.java", "sha1", SIMPLE_CLASS);

        List<Chunk> chunks = result.chunks();
        assertThat(chunks).isNotEmpty();
        // method-level chunk
        assertThat(chunks).anySatisfy(c -> {
            assertThat(c.domain()).isEqualTo(Domain.CODE);
            assertThat(c.type()).isEqualTo("METHOD");
            assertThat(c.fqn()).isEqualTo("com.acme.PaymentService#settle(String)");
            assertThat(c.text()).contains("settle");
        });
        // class summary chunk
        assertThat(chunks).anySatisfy(c ->
            assertThat(c.type()).isEqualTo("CLASS")
        );
    }

    @Test
    void parse_javaClass_emitsNodeAndEdgeEvents() {
        var result = parser.parse("repo", "src/PaymentService.java", "sha1", SIMPLE_CLASS);

        List<GraphEvent> events = result.events();
        // Class node
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.NodeEvent.class);
            var ne = (GraphEvent.NodeEvent) e;
            assertThat(ne.id()).isEqualTo("com.acme.PaymentService");
            assertThat(ne.label()).isEqualTo("Class");
        });
        // Method node
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.NodeEvent.class);
            var ne = (GraphEvent.NodeEvent) e;
            assertThat(ne.id()).contains("settle");
        });
        // DECLARES edge
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(GraphEvent.EdgeEvent.class);
            var ee = (GraphEvent.EdgeEvent) e;
            assertThat(ee.type()).isEqualTo(GraphEdge.EdgeType.DECLARES);
            assertThat(ee.from()).isEqualTo("com.acme.PaymentService");
        });
    }

    @Test
    void parse_serviceAnnotation_tagsNodeAsBean() {
        var result = parser.parse("repo", "src/PaymentService.java", "sha1", SIMPLE_CLASS);

        assertThat(result.events()).anySatisfy(e -> {
            if (e instanceof GraphEvent.NodeEvent ne && ne.id().equals("com.acme.PaymentService")) {
                assertThat(ne.tags()).contains("BEAN");
            }
        });
    }

    @Test
    void parse_restControllerAnnotation_tagsAsRestController() {
        String controller = """
            package com.acme;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class PaymentController {
                @PostMapping("/pay")
                public void pay() {}
            }
            """;
        var result = parser.parse("repo", "src/PaymentController.java", "sha1", controller);

        assertThat(result.events()).anySatisfy(e -> {
            if (e instanceof GraphEvent.NodeEvent ne && ne.id().equals("com.acme.PaymentController")) {
                assertThat(ne.tags()).contains("REST_CONTROLLER");
            }
        });
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=JavaSourceParserTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement JavaSourceParser**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java
package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;

import java.util.*;

public final class JavaSourceParser {

    private static final Set<String> BEAN_ANNOTATIONS = Set.of("Service", "Component", "Repository", "Controller");
    private static final Set<String> MAPPING_ANNOTATIONS = Set.of(
        "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    public record ParseResult(List<Chunk> chunks, List<GraphEvent> events) {}

    public ParseResult parse(String repo, String path, String gitSha, String source) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(source);
        } catch (Exception e) {
            return new ParseResult(List.of(), List.of());
        }

        List<Chunk> chunks = new ArrayList<>();
        List<GraphEvent> events = new ArrayList<>();
        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString()).orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
            String typeFqn = packageName.isEmpty()
                ? type.getNameAsString()
                : packageName + "." + type.getNameAsString();

            // Collect type-level tags
            Set<String> typeTags = new HashSet<>();
            for (AnnotationExpr ann : type.getAnnotations()) {
                String name = ann.getNameAsString();
                if ("RestController".equals(name) || "Controller".equals(name)) typeTags.add("REST_CONTROLLER");
                if ("Repository".equals(name)) typeTags.add("REPOSITORY");
                if (BEAN_ANNOTATIONS.contains(name)) typeTags.add("BEAN");
            }

            String label = type.isInterface() ? "Interface" : "Class";
            String javadoc = type.getJavadoc().map(j -> j.toText()).orElse(null);
            events.add(new GraphEvent.NodeEvent(typeFqn, label, Set.copyOf(typeTags),
                type.getNameAsString(), javadoc));

            // Class summary chunk
            String classText = typeFqn + "\n" + (javadoc != null ? javadoc : "");
            chunks.add(new Chunk(typeFqn, repo, path, Domain.CODE, "CLASS",
                typeFqn, null, null, List.of(), gitSha,
                MarkdownParser.hash(classText), "java", classText, null));

            // Methods
            for (MethodDeclaration method : type.getMethods()) {
                String sig = method.getSignature().asString();
                String mFqn = typeFqn + "#" + sig;

                events.add(new GraphEvent.EdgeEvent(typeFqn, mFqn, GraphEdge.EdgeType.DECLARES));

                Set<String> methodTags = new HashSet<>();
                for (AnnotationExpr ann : method.getAnnotations()) {
                    String name = ann.getNameAsString();
                    if (MAPPING_ANNOTATIONS.contains(name)) methodTags.add("ENDPOINT");
                    if ("KafkaListener".equals(name)) methodTags.add("KAFKA_CONSUMER");
                    if ("KafkaHandler".equals(name)) methodTags.add("KAFKA_HANDLER");
                }

                String methodJavadoc = method.getJavadoc().map(j -> j.toText()).orElse(null);
                String methodSig = method.getDeclarationAsString(false, true, true);
                events.add(new GraphEvent.NodeEvent(mFqn, "Method",
                    Set.copyOf(methodTags), methodSig, methodJavadoc));

                String methodText = methodSig + "\n"
                    + (methodJavadoc != null ? methodJavadoc + "\n" : "")
                    + method.getBody().map(Object::toString).orElse("");

                chunks.add(new Chunk(mFqn, repo, path, Domain.CODE, "METHOD",
                    mFqn, null, null, List.of(), gitSha,
                    MarkdownParser.hash(methodText), "java", methodText, null));

                // Unresolved CALLS edges (best-effort without SymbolSolver classpath)
                method.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
                    String callee = call.getScope()
                        .map(s -> s + "." + call.getNameAsString())
                        .orElse(call.getNameAsString());
                    events.add(new GraphEvent.EdgeEvent(mFqn, callee, GraphEdge.EdgeType.CALLS));
                });
            }
        });

        return new ParseResult(chunks, events);
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
mvn test -Dtest=JavaSourceParserTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java \
        src/test/java/com/acme/airetrieval/ingest/parser/JavaSourceParserTest.java
git commit -m "feat: JavaSourceParser — AST → method/class Chunks + GraphEvents (DECLARES, CALLS, BEAN/ENDPOINT tags)"
```

---

### Task 4: CodeGraphStore (JGraphT)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/CodeGraphStore.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/GraphExtractor.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/graph/CodeGraphStoreTest.java`

- [ ] **Step 1: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/graph/CodeGraphStoreTest.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class CodeGraphStoreTest {

    @Test
    void addNodeAndEdge_canQueryNeighbors() {
        var store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("A", "Class", Set.of(), "class A", null));
        store.applyEvent(new GraphEvent.NodeEvent("B", "Method", Set.of(), "void b()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("A", "B", GraphEdge.EdgeType.DECLARES));

        assertThat(store.getNode("A")).isPresent();
        assertThat(store.getOutEdges("A")).hasSize(1);
        assertThat(store.getOutEdges("A").get(0).to()).isEqualTo("B");
    }

    @Test
    void tagEvent_addsTagToNode() {
        var store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("A", "Class", Set.of(), "class A", null));
        store.applyEvent(new GraphEvent.TagEvent("A", "REST_CONTROLLER"));

        var node = store.getNode("A");
        assertThat(node).isPresent();
        assertThat(node.get().tags()).contains("REST_CONTROLLER");
    }

    @Test
    void getInEdges_returnsCallers() {
        var store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("caller", "Method", Set.of(), "void caller()", null));
        store.applyEvent(new GraphEvent.NodeEvent("callee", "Method", Set.of(), "void callee()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("caller", "callee", GraphEdge.EdgeType.CALLS));

        assertThat(store.getInEdges("callee"))
            .anyMatch(e -> e.from().equals("caller") && e.type() == GraphEdge.EdgeType.CALLS);
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
mvn test -Dtest=CodeGraphStoreTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 3: Implement CodeGraphStore**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/CodeGraphStore.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.graph.model.GraphNode;
import org.jgrapht.graph.DirectedMultigraph;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CodeGraphStore {

    private final DirectedMultigraph<String, LabeledEdge> graph =
        new DirectedMultigraph<>(LabeledEdge.class);
    private final Map<String, MutableNode> nodes = new ConcurrentHashMap<>();

    public void applyEvent(GraphEvent event) {
        switch (event) {
            case GraphEvent.NodeEvent ne -> {
                graph.addVertex(ne.id());
                nodes.merge(ne.id(), new MutableNode(ne.id(), ne.label(), new HashSet<>(ne.tags()),
                    ne.signature(), ne.javadoc()), (existing, incoming) -> {
                    existing.tags().addAll(incoming.tags());
                    return existing;
                });
            }
            case GraphEvent.EdgeEvent ee -> {
                graph.addVertex(ee.from());
                graph.addVertex(ee.to());
                graph.addEdge(ee.from(), ee.to(), new LabeledEdge(ee.from(), ee.to(), ee.type()));
            }
            case GraphEvent.TagEvent te -> {
                if (nodes.containsKey(te.id())) nodes.get(te.id()).tags().add(te.tag());
            }
        }
    }

    public Optional<GraphNode> getNode(String id) {
        MutableNode mn = nodes.get(id);
        if (mn == null) return Optional.empty();
        return Optional.of(new GraphNode(mn.id(), mn.label(), Set.copyOf(mn.tags()),
            mn.signature(), mn.javadoc()));
    }

    public List<GraphEdge> getOutEdges(String id) {
        if (!graph.containsVertex(id)) return List.of();
        return graph.outgoingEdgesOf(id).stream()
            .map(e -> new GraphEdge(e.from(), e.to(), e.type()))
            .toList();
    }

    public List<GraphEdge> getInEdges(String id) {
        if (!graph.containsVertex(id)) return List.of();
        return graph.incomingEdgesOf(id).stream()
            .map(e -> new GraphEdge(e.from(), e.to(), e.type()))
            .toList();
    }

    public List<GraphEdge> getEdgesByType(String id, GraphEdge.EdgeType type, boolean outgoing) {
        return (outgoing ? getOutEdges(id) : getInEdges(id)).stream()
            .filter(e -> e.type() == type)
            .toList();
    }

    public Set<String> getNodesByTag(String tag) {
        return nodes.entrySet().stream()
            .filter(en -> en.getValue().tags().contains(tag))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    // Internal mutable node for merging tags
    private static class MutableNode {
        final String id, label, signature, javadoc;
        final Set<String> tags;
        MutableNode(String id, String label, Set<String> tags, String signature, String javadoc) {
            this.id = id; this.label = label; this.tags = tags;
            this.signature = signature; this.javadoc = javadoc;
        }
        String id() { return id; }
        String label() { return label; }
        Set<String> tags() { return tags; }
        String signature() { return signature; }
        String javadoc() { return javadoc; }
    }

    // JGraphT needs an edge class
    static class LabeledEdge {
        final String from, to;
        final GraphEdge.EdgeType type;
        LabeledEdge(String from, String to, GraphEdge.EdgeType type) {
            this.from = from; this.to = to; this.type = type;
        }
    }
}
```

- [ ] **Step 4: Implement GraphExtractor**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/GraphExtractor.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEvent;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GraphExtractor {

    private final CodeGraphStore store;

    public GraphExtractor(CodeGraphStore store) {
        this.store = store;
    }

    public void apply(List<GraphEvent> events) {
        for (GraphEvent event : events) {
            store.applyEvent(event);
        }
    }
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
mvn test -Dtest=CodeGraphStoreTest -q
```
Expected: Tests run: 3, Failures: 0, Errors: 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/ \
        src/test/java/com/acme/airetrieval/graph/CodeGraphStoreTest.java
git commit -m "feat: CodeGraphStore (JGraphT) + GraphExtractor — apply graph events in-memory"
```

---

### Task 5: GraphQueries + DTOs

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/dto/SymbolView.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/dto/KafkaFlow.java`
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/graph/GraphQueries.java`
- Create: `ai-retrieval/src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java`

- [ ] **Step 1: Create DTOs**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/dto/SymbolView.java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SymbolView(
    String fqn,
    String signature,
    String javadoc,
    String body,        // nullable — the method text from index
    List<String> callerSignatures,
    List<String> calleeSignatures
) {}
```

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/retrieve/dto/KafkaFlow.java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record KafkaFlow(
    String topic,
    List<String> producerSignatures,
    List<String> consumerSignatures
) {}
```

- [ ] **Step 2: Write failing test**

```java
// ai-retrieval/src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class GraphQueriesTest {

    CodeGraphStore store;
    GraphQueries queries;

    @BeforeEach
    void setUp() {
        store = new CodeGraphStore();
        queries = new GraphQueries(store);

        store.applyEvent(new GraphEvent.NodeEvent("com.acme.A", "Class", Set.of("BEAN"), "class A", null));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.A#foo()", "Method", Set.of(), "void foo()", "Does foo"));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.A#bar()", "Method", Set.of(), "void bar()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("com.acme.A", "com.acme.A#foo()", GraphEdge.EdgeType.DECLARES));
        store.applyEvent(new GraphEvent.EdgeEvent("com.acme.A#foo()", "com.acme.A#bar()", GraphEdge.EdgeType.CALLS));
    }

    @Test
    void getSymbolView_knownFqn_returnsSignatureAndRelations() {
        SymbolView view = queries.getSymbolView("com.acme.A#foo()");

        assertThat(view).isNotNull();
        assertThat(view.fqn()).isEqualTo("com.acme.A#foo()");
        assertThat(view.signature()).isEqualTo("void foo()");
        assertThat(view.javadoc()).isEqualTo("Does foo");
        assertThat(view.calleeSignatures()).containsExactly("void bar()");
    }

    @Test
    void getCallers_returnsMethodsThatCallTarget() {
        var callers = queries.getCallers("com.acme.A#bar()", 1);
        assertThat(callers).containsExactly("void foo()");
    }

    @Test
    void getCallees_returnsMethodsCalledByTarget() {
        var callees = queries.getCallees("com.acme.A#foo()", 1);
        assertThat(callees).containsExactly("void bar()");
    }

    @Test
    void getSymbolView_unknownFqn_returnsNull() {
        assertThat(queries.getSymbolView("does.not.Exist#method()")).isNull();
    }
}
```

- [ ] **Step 3: Run test — verify it fails**

```bash
mvn test -Dtest=GraphQueriesTest -q 2>&1 | tail -5
```
Expected: FAIL.

- [ ] **Step 4: Implement GraphQueries**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/graph/GraphQueries.java
package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphNode;
import com.acme.airetrieval.retrieve.dto.KafkaFlow;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class GraphQueries {

    private final CodeGraphStore store;

    public GraphQueries(CodeGraphStore store) {
        this.store = store;
    }

    public SymbolView getSymbolView(String fqn) {
        return store.getNode(fqn).map(node -> {
            List<String> callerSigs = store.getInEdges(fqn).stream()
                .filter(e -> e.type() == GraphEdge.EdgeType.CALLS)
                .map(e -> store.getNode(e.from())
                    .map(GraphNode::signature).orElse(e.from()))
                .toList();

            List<String> calleeSigs = store.getOutEdges(fqn).stream()
                .filter(e -> e.type() == GraphEdge.EdgeType.CALLS)
                .map(e -> store.getNode(e.to())
                    .map(GraphNode::signature).orElse(e.to()))
                .toList();

            return new SymbolView(fqn, node.signature(), node.javadoc(),
                null, callerSigs, calleeSigs);
        }).orElse(null);
    }

    public List<String> getCallers(String fqn, int depth) {
        return collectNeighbors(fqn, depth, false, GraphEdge.EdgeType.CALLS);
    }

    public List<String> getCallees(String fqn, int depth) {
        return collectNeighbors(fqn, depth, true, GraphEdge.EdgeType.CALLS);
    }

    public KafkaFlow getKafkaFlow(String topic) {
        List<String> producers = store.getNodesByTag("KAFKA_PRODUCER").stream()
            .filter(id -> store.getOutEdges(id).stream()
                .anyMatch(e -> e.to().equals(topic) && e.type() == GraphEdge.EdgeType.PRODUCES))
            .map(id -> store.getNode(id).map(GraphNode::signature).orElse(id))
            .toList();

        List<String> consumers = store.getNodesByTag("KAFKA_CONSUMER").stream()
            .filter(id -> store.getInEdges(id).stream()
                .anyMatch(e -> e.from().equals(topic) && e.type() == GraphEdge.EdgeType.CONSUMES))
            .map(id -> store.getNode(id).map(GraphNode::signature).orElse(id))
            .toList();

        return new KafkaFlow(topic, producers, consumers);
    }

    private List<String> collectNeighbors(String start, int depth,
                                           boolean outgoing, GraphEdge.EdgeType type) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>(List.of(start));
        int currentDepth = 0;

        while (!queue.isEmpty() && currentDepth < depth) {
            int size = queue.size();
            for (int i = 0; i < size; i++) {
                String node = queue.poll();
                List<GraphEdge> edges = outgoing
                    ? store.getEdgesByType(node, type, true)
                    : store.getEdgesByType(node, type, false);
                for (GraphEdge edge : edges) {
                    String neighbor = outgoing ? edge.to() : edge.from();
                    if (!neighbor.equals(start) && visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            currentDepth++;
        }

        return visited.stream()
            .map(id -> store.getNode(id).map(GraphNode::signature).orElse(id))
            .toList();
    }
}
```

- [ ] **Step 5: Run test — verify it passes**

```bash
mvn test -Dtest=GraphQueriesTest -q
```
Expected: Tests run: 4, Failures: 0, Errors: 0.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/acme/airetrieval/graph/GraphQueries.java \
        src/main/java/com/acme/airetrieval/retrieve/dto/ \
        src/test/java/com/acme/airetrieval/graph/GraphQueriesTest.java
git commit -m "feat: GraphQueries — getSymbolView, getCallers/Callees, getKafkaFlow"
```

---

### Task 6: Wire JavaSourceParser into IndexService

**Files:**
- Modify: `ai-retrieval/src/main/java/com/acme/airetrieval/ingest/IndexService.java`

- [ ] **Step 1: Update IndexService to call JavaSourceParser and GraphExtractor**

Inject `JavaSourceParser` and `GraphExtractor` into `IndexService`. In the indexing loop, add:
```java
// After the existing switch statement, add JAVA case:
case JAVA -> {
    String content = Files.readString(fullPath);
    var result = javaSourceParser.parse(repo, change.path(), toSha, content);
    graphExtractor.apply(result.events());
    yield result.chunks();
}
```

Updated constructor:
```java
private final JavaSourceParser javaSourceParser;
private final GraphExtractor graphExtractor;

public IndexService(GitChangeDetector changeDetector, SourceClassifier classifier,
                    MarkdownParser markdownParser, DocumentParser documentParser,
                    JavaSourceParser javaSourceParser, GraphExtractor graphExtractor,
                    LuceneIndexer indexer, EmbeddingModel embeddingModel,
                    LuceneSearcher searcher) {
    // ... assign all fields
    this.javaSourceParser = javaSourceParser;
    this.graphExtractor = graphExtractor;
}
```

Add `@Bean` for `JavaSourceParser` in `LuceneConfig`:
```java
@Bean
public JavaSourceParser javaSourceParser() {
    return new JavaSourceParser();
}
```

- [ ] **Step 2: Compile and run full test suite**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/IndexService.java \
        src/main/java/com/acme/airetrieval/config/LuceneConfig.java
git commit -m "feat: wire JavaSourceParser + GraphExtractor into IndexService — JAVA files now index code graph"
```

---

### Task 7: MCP server — McpTools

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/mcp/McpTools.java`

- [ ] **Step 1: Create McpTools**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/mcp/McpTools.java
package com.acme.airetrieval.mcp;

import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.retrieve.RetrievalOrchestrator;
import com.acme.airetrieval.retrieve.dto.KafkaFlow;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpTools {

    private final RetrievalOrchestrator retrieval;
    private final GraphQueries graph;

    public McpTools(RetrievalOrchestrator retrieval, GraphQueries graph) {
        this.retrieval = retrieval;
        this.graph = graph;
    }

    @Tool(description = "Hybrid BM25+semantic search over all code and documents. " +
        "Returns compact ranked hits with path, type, fqn, and a 300-char snippet. " +
        "Use this as the default starting point for any question.")
    public List<SearchHit> semantic_search(
            @ToolParam(description = "Natural language or keyword query") String query,
            @ToolParam(description = "Max results (1-20, default 8)", required = false) Integer k)
            throws Exception {
        return retrieval.hybrid(query, null, k != null ? Math.min(k, 20) : 8);
    }

    @Tool(description = "Hybrid search over CODE domain only — Java methods, classes, endpoints.")
    public List<SearchHit> search_code(
            @ToolParam(description = "Query") String query,
            @ToolParam(description = "Repository name to filter (optional)", required = false) String repo,
            @ToolParam(description = "Max results (default 8)", required = false) Integer k)
            throws Exception {
        var filter = new SearchFilter(repo, "CODE", null, null);
        return retrieval.hybrid(query, filter, k != null ? k : 8);
    }

    @Tool(description = "Hybrid search over KNOWLEDGE domain — Markdown, PDF, DOCX, ADRs, specs.")
    public List<SearchHit> search_knowledge(
            @ToolParam(description = "Query") String query,
            @ToolParam(description = "Repository name to filter (optional)", required = false) String repo,
            @ToolParam(description = "Max results (default 8)", required = false) Integer k)
            throws Exception {
        var filter = new SearchFilter(repo, "KNOWLEDGE", null, null);
        return retrieval.hybrid(query, filter, k != null ? k : 8);
    }

    @Tool(description = "Get a symbol's full signature, javadoc, and immediate callers/callees " +
        "(signatures only — use this to understand a specific method or class without reading whole files).")
    public SymbolView get_symbol(
            @ToolParam(description = "Fully-qualified name, e.g. com.acme.PaymentService#settle(String)") String fqn) {
        return graph.getSymbolView(fqn);
    }

    @Tool(description = "List methods that call the given fqn (up to specified depth). Returns signatures only.")
    public List<String> get_callers(
            @ToolParam(description = "Fully-qualified method name") String fqn,
            @ToolParam(description = "Traversal depth (default 1)", required = false) Integer depth) {
        return graph.getCallers(fqn, depth != null ? depth : 1);
    }

    @Tool(description = "List methods called by the given fqn (up to specified depth). Returns signatures only.")
    public List<String> get_callees(
            @ToolParam(description = "Fully-qualified method name") String fqn,
            @ToolParam(description = "Traversal depth (default 1)", required = false) Integer depth) {
        return graph.getCallees(fqn, depth != null ? depth : 1);
    }

    @Tool(description = "List all producers and consumers for a Kafka topic by name. Returns handler signatures.")
    public KafkaFlow get_kafka_flow(
            @ToolParam(description = "Kafka topic name") String topic) {
        return graph.getKafkaFlow(topic);
    }
}
```

- [ ] **Step 2: Register McpTools as Spring AI tool provider**

Spring AI's MCP server starter auto-discovers `@Component` beans with `@Tool` methods. No extra config needed beyond `application.yml` settings already set. Verify by checking that `McpTools` is a `@Component`.

- [ ] **Step 3: Compile**

```bash
mvn compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/mcp/McpTools.java
git commit -m "feat: McpTools — @Tool methods for semantic_search, search_code, get_symbol, get_callers/callees, get_kafka_flow"
```

---

### Task 8: GraphController (REST mirrors)

**Files:**
- Create: `ai-retrieval/src/main/java/com/acme/airetrieval/api/GraphController.java`

- [ ] **Step 1: Create GraphController**

```java
// ai-retrieval/src/main/java/com/acme/airetrieval/api/GraphController.java
package com.acme.airetrieval.api;

import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.retrieve.dto.KafkaFlow;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final GraphQueries graph;

    public GraphController(GraphQueries graph) {
        this.graph = graph;
    }

    @GetMapping("/symbol/{fqn}")
    public ResponseEntity<SymbolView> getSymbol(@PathVariable String fqn) {
        SymbolView view = graph.getSymbolView(fqn);
        return view != null ? ResponseEntity.ok(view) : ResponseEntity.notFound().build();
    }

    @GetMapping("/callers/{fqn}")
    public ResponseEntity<List<String>> getCallers(
            @PathVariable String fqn,
            @RequestParam(defaultValue = "1") int depth) {
        return ResponseEntity.ok(graph.getCallers(fqn, depth));
    }

    @GetMapping("/callees/{fqn}")
    public ResponseEntity<List<String>> getCallees(
            @PathVariable String fqn,
            @RequestParam(defaultValue = "1") int depth) {
        return ResponseEntity.ok(graph.getCallees(fqn, depth));
    }

    @GetMapping("/kafka/topic/{topic}")
    public ResponseEntity<KafkaFlow> getKafkaFlow(@PathVariable String topic) {
        return ResponseEntity.ok(graph.getKafkaFlow(topic));
    }
}
```

- [ ] **Step 2: Run full test suite**

```bash
mvn test -q
```
Expected: All tests pass.

- [ ] **Step 3: Verify MCP server starts**

```bash
mvn spring-boot:run &
sleep 8
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/v1/graph/callers/com.acme.PaymentService%23settle%28String%29
kill %1
```
Expected: health=UP, callers=[] (empty graph until indexed).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/api/GraphController.java
git commit -m "feat: GraphController — REST mirrors for symbol/callers/callees/kafka endpoints"
```

---

### Phase 2 Exit Criterion

Run:
```bash
mvn test -q && mvn spring-boot:run &
sleep 8
curl -s http://localhost:8080/api/v1/graph/kafka/topic/orders
curl -s http://localhost:8080/actuator/health
kill %1
```

**All tests pass + server starts + REST graph endpoints respond = Phase 2 complete.**

MCP server is now available to Claude Code via `stdio`. Connect by adding the JAR to `.claude/mcp.json`:
```json
{
  "mcpServers": {
    "kira": {
      "command": "java",
      "args": ["-jar", "/path/to/ai-retrieval-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

Proceed to `2026-06-15-phase3-precision.md`.
