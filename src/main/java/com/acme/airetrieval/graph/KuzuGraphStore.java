package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.graph.model.GraphNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class KuzuGraphStore implements GraphStore {
    public KuzuGraphStore(Path dataDir) {
        throw new UnsupportedOperationException(
            "KuzuGraphStore requires kuzu-java:0.6.0 and a native binding implementation. " +
            "dataDir=" + dataDir + ". Set kira.graph.engine=jgrapht to use the in-memory store.");
    }

    @Override public void applyEvent(GraphEvent event) {}
    @Override public Optional<GraphNode> getNode(String id) { return Optional.empty(); }
    @Override public List<GraphEdge> getOutEdges(String id) { return List.of(); }
    @Override public List<GraphEdge> getInEdges(String id) { return List.of(); }
    @Override public List<GraphEdge> getEdgesByType(String id, GraphEdge.EdgeType type, boolean outgoing) { return List.of(); }
    @Override public Set<String> getNodesByTag(String tag) { return Set.of(); }
}
