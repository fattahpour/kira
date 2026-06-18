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
