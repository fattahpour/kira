package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphQueriesTest {
    private CodeGraphStore store;
    private GraphQueries queries;

    @BeforeEach
    void setup() {
        store = new CodeGraphStore();
        queries = new GraphQueries(store);
        store.applyEvent(new GraphEvent.NodeEvent("com.A#foo", "METHOD", Set.of(), "void foo()", "does foo"));
        store.applyEvent(new GraphEvent.NodeEvent("com.B#bar", "METHOD", Set.of(), "void bar()", "does bar"));
        store.applyEvent(new GraphEvent.EdgeEvent("com.B#bar", "com.A#foo", GraphEdge.EdgeType.CALLS));
    }

    @Test
    void getSymbolView_returnsSignatureAndJavadoc() {
        SymbolView view = queries.getSymbolView("com.A#foo");
        assertThat(view.signature()).isEqualTo("void foo()");
        assertThat(view.javadoc()).isEqualTo("does foo");
    }

    @Test
    void getCallers_returnsCallerSignatures() {
        assertThat(queries.getCallers("com.A#foo", 1)).isEqualTo(List.of("void bar()"));
    }

    @Test
    void getCallees_returnsCalleeSignatures() {
        assertThat(queries.getCallees("com.B#bar", 1)).isEqualTo(List.of("void foo()"));
    }

    @Test
    void expandContext_returns_neighbor_signatures() {
        CodeGraphStore store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("A", "classA", Set.of(), "void A()", null));
        store.applyEvent(new GraphEvent.NodeEvent("B", "classB", Set.of(), "void B()", null));
        store.applyEvent(new GraphEvent.NodeEvent("C", "classC", Set.of(), "void C()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("A", "B", GraphEdge.EdgeType.CALLS));
        store.applyEvent(new GraphEvent.EdgeEvent("B", "C", GraphEdge.EdgeType.CALLS));
        GraphQueries q = new GraphQueries(store);

        List<String> hop1 = q.expandContext(List.of("A"), 1);
        assertThat(hop1).contains("void B()");
        assertThat(hop1).doesNotContain("void A()");
        assertThat(hop1).doesNotContain("void C()");

        List<String> hop2 = q.expandContext(List.of("A"), 2);
        assertThat(hop2).contains("void B()", "void C()");
        assertThat(hop2).doesNotContain("void A()");
    }

    @Test
    void getEndpoint_knownEndpoint_returnsEndpointInfo() {
        store.applyEvent(new GraphEvent.NodeEvent("POST /api/pay", "Endpoint",
            Set.of("ENDPOINT"), "POST /api/pay", "operationPay"));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.PaymentController#pay()", "Method",
            Set.of("ENDPOINT"), "public void pay()", null));
        store.applyEvent(new GraphEvent.EdgeEvent(
            "com.acme.PaymentController#pay()", "POST /api/pay", GraphEdge.EdgeType.EXPOSES));

        var result = queries.getEndpoint("POST", "/api/pay");
        assertThat(result).isPresent();
        assertThat(result.get().method()).isEqualTo("POST");
        assertThat(result.get().path()).isEqualTo("/api/pay");
        assertThat(result.get().operationId()).isEqualTo("operationPay");
        assertThat(result.get().handlerFqn()).isEqualTo("com.acme.PaymentController#pay()");
    }

    @Test
    void getEndpoint_unknownEndpoint_returnsEmpty() {
        assertThat(queries.getEndpoint("GET", "/does/not/exist")).isEmpty();
    }

    @Test
    void getBeanGraph_knownBean_returnsDependencies() {
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.OrderService", "Class",
            Set.of("BEAN"), "class OrderService", null));
        store.applyEvent(new GraphEvent.NodeEvent("PaymentService", "Class",
            Set.of("BEAN"), "class PaymentService", null));
        store.applyEvent(new GraphEvent.EdgeEvent(
            "com.acme.OrderService", "PaymentService", GraphEdge.EdgeType.DEPENDS_ON));

        var result = queries.getBeanGraph("OrderService", 1);
        assertThat(result.root()).isEqualTo("com.acme.OrderService");
        assertThat(result.dependencies()).hasSize(1);
        assertThat(result.dependencies().get(0).beanFqn()).isEqualTo("PaymentService");
        assertThat(result.dependencies().get(0).depth()).isEqualTo(1);
    }

    @Test
    void getBeanGraph_unknownBean_returnsEmptyDeps() {
        var result = queries.getBeanGraph("NonExistent", 2);
        assertThat(result.dependencies()).isEmpty();
    }

    @Test
    void getBeanGraph_depth2_traversesTransitiveDeps() {
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.OrderService", "Class",
            Set.of("BEAN"), "class OrderService", null));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.PaymentService", "Class",
            Set.of("BEAN"), "class PaymentService", null));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.RepoClient", "Class",
            Set.of("BEAN"), "class RepoClient", null));

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

    @Test
    void getEndpointKeys_returnsOnlyExposesReachableEndpoints() {
        store.applyEvent(new GraphEvent.NodeEvent("GET /api/items", "Endpoint",
            Set.of("ENDPOINT"), "GET /api/items", null));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.ItemController#list()", "Method",
            Set.of("ENDPOINT"), "public List<Item> list()", null));
        store.applyEvent(new GraphEvent.EdgeEvent(
            "com.acme.ItemController#list()", "GET /api/items", GraphEdge.EdgeType.EXPOSES));

        store.applyEvent(new GraphEvent.NodeEvent("POST /api/items", "Endpoint",
            Set.of("ENDPOINT"), "POST /api/items", null));
        store.applyEvent(new GraphEvent.EdgeEvent(
            "spec.yaml", "POST /api/items", GraphEdge.EdgeType.SPECIFIES));

        Set<String> implKeys = queries.getEndpointKeys(null);
        assertThat(implKeys).contains("GET /api/items");
        assertThat(implKeys).doesNotContain("POST /api/items");
    }

    @Test
    void getEndpointKeys_scopedToRepo_excludesOtherRepos() {
        store.applyEvent(new GraphEvent.NodeEvent("GET /api/alpha", "Endpoint",
            Set.of("ENDPOINT", "REPO:alpha"), "GET /api/alpha", null));
        store.applyEvent(new GraphEvent.NodeEvent("com.acme.AlphaController#list()", "Method",
            Set.of(), "public List<?> list()", null));
        store.applyEvent(new GraphEvent.EdgeEvent(
            "com.acme.AlphaController#list()", "GET /api/alpha", GraphEdge.EdgeType.EXPOSES));

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

    @Test
    void expandContext_respectsMaxSignaturesLimit() {
        CodeGraphStore store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("A", "METHOD", Set.of(), "void A()", null));
        for (int i = 1; i <= 10; i++) {
            store.applyEvent(new GraphEvent.NodeEvent("B" + i, "METHOD", Set.of(), "void B" + i + "()", null));
            store.applyEvent(new GraphEvent.EdgeEvent("A", "B" + i, GraphEdge.EdgeType.CALLS));
        }
        GraphQueries q = new GraphQueries(store);

        assertThat(q.expandContext(List.of("A"), 1, 3)).hasSize(3);
        assertThat(q.expandContext(List.of("A"), 1)).hasSize(10);
    }
}
