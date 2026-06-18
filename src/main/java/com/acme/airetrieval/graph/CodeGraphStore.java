package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphEvent;
import com.acme.airetrieval.graph.model.GraphNode;
import org.jgrapht.graph.DirectedMultigraph;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class CodeGraphStore implements GraphStore {
    private final DirectedMultigraph<String, LabeledEdge> graph = new DirectedMultigraph<>(LabeledEdge.class);
    private final Map<String, MutableNode> nodes = new ConcurrentHashMap<>();

    @Override
    public synchronized void applyEvent(GraphEvent event) {
        switch (event) {
            case GraphEvent.NodeEvent node -> {
                graph.addVertex(node.id());
                nodes.merge(node.id(), new MutableNode(node.id(), node.label(), new HashSet<>(node.tags()),
                    node.signature(), node.javadoc()), (existing, incoming) -> {
                    existing.tags.addAll(incoming.tags);
                    existing.label = incoming.label;
                    existing.signature = incoming.signature;
                    existing.javadoc = incoming.javadoc;
                    return existing;
                });
            }
            case GraphEvent.EdgeEvent edge -> {
                graph.addVertex(edge.from());
                graph.addVertex(edge.to());
                graph.addEdge(edge.from(), edge.to(), new LabeledEdge(edge.from(), edge.to(), edge.type()));
            }
            case GraphEvent.TagEvent tag -> {
                MutableNode node = nodes.get(tag.id());
                if (node != null) node.tags.add(tag.tag());
            }
        }
    }

    @Override
    public Optional<GraphNode> getNode(String id) {
        MutableNode node = nodes.get(id);
        if (node == null) return Optional.empty();
        return Optional.of(new GraphNode(node.id, node.label, Set.copyOf(node.tags), node.signature, node.javadoc));
    }

    @Override
    public List<GraphEdge> getOutEdges(String id) {
        if (!graph.containsVertex(id)) return List.of();
        return graph.outgoingEdgesOf(id).stream().map(LabeledEdge::toGraphEdge).toList();
    }

    @Override
    public List<GraphEdge> getInEdges(String id) {
        if (!graph.containsVertex(id)) return List.of();
        return graph.incomingEdgesOf(id).stream().map(LabeledEdge::toGraphEdge).toList();
    }

    @Override
    public List<GraphEdge> getEdgesByType(String id, GraphEdge.EdgeType type, boolean outgoing) {
        return (outgoing ? getOutEdges(id) : getInEdges(id)).stream().filter(e -> e.type() == type).toList();
    }

    @Override
    public Set<String> getNodesByTag(String tag) {
        return nodes.entrySet().stream()
            .filter(e -> e.getValue().tags.contains(tag))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    private static final class MutableNode {
        final String id;
        String label;
        final Set<String> tags;
        String signature;
        String javadoc;
        MutableNode(String id, String label, Set<String> tags, String signature, String javadoc) {
            this.id = id; this.label = label; this.tags = tags; this.signature = signature; this.javadoc = javadoc;
        }
    }

    public static final class LabeledEdge {
        private String from;
        private String to;
        private GraphEdge.EdgeType type;
        public LabeledEdge() {}
        LabeledEdge(String from, String to, GraphEdge.EdgeType type) {
            this.from = from; this.to = to; this.type = type;
        }
        GraphEdge toGraphEdge() { return new GraphEdge(from, to, type); }
    }
}
