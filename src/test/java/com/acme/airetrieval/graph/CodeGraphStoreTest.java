package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGraphStoreTest {
    @Test
    void storesNodesEdgesAndTags() {
        var store = new CodeGraphStore();
        store.applyEvent(new GraphEvent.NodeEvent("A", "Class", Set.of(), "class A", null));
        store.applyEvent(new GraphEvent.NodeEvent("B", "Method", Set.of(), "void b()", null));
        store.applyEvent(new GraphEvent.EdgeEvent("A", "B", GraphEdge.EdgeType.DECLARES));
        store.applyEvent(new GraphEvent.TagEvent("A", "BEAN"));

        assertThat(store.getNode("A")).isPresent();
        assertThat(store.getNode("A").orElseThrow().tags()).contains("BEAN");
        assertThat(store.getOutEdges("A")).hasSize(1);
        assertThat(store.getInEdges("B")).hasSize(1);
    }
}
