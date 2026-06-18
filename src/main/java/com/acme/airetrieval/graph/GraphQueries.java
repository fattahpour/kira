package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEdge;
import com.acme.airetrieval.graph.model.GraphNode;
import com.acme.airetrieval.ingest.model.EndpointInfo;
import com.acme.airetrieval.retrieve.dto.BeanDep;
import com.acme.airetrieval.retrieve.dto.BeanGraph;
import com.acme.airetrieval.retrieve.dto.KafkaFlow;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class GraphQueries {
    private final GraphStore store;

    public GraphQueries(GraphStore store) {
        this.store = store;
    }

    public SymbolView getSymbolView(String fqn) {
        var node = store.getNode(fqn).orElse(null);
        List<String> callers = getCallers(fqn, 1);
        List<String> callees = getCallees(fqn, 1);
        if (node == null) return new SymbolView(fqn, null, null, null, callers, callees);
        return new SymbolView(node.id(), node.signature(), node.javadoc(), null, callers, callees);
    }

    public List<String> getCallers(String fqn, int depth) {
        return bfsSignatures(fqn, depth, false);
    }

    public List<String> getCallees(String fqn, int depth) {
        return bfsSignatures(fqn, depth, true);
    }

    private List<String> bfsSignatures(String startFqn, int depth, boolean outgoing) {
        List<String> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Deque<Integer> levels = new ArrayDeque<>();
        queue.add(startFqn);
        levels.add(0);
        visited.add(startFqn);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int level = levels.poll();
            if (level >= depth) continue;
            List<GraphEdge> edges = store.getEdgesByType(current, GraphEdge.EdgeType.CALLS, outgoing);
            for (GraphEdge edge : edges) {
                String neighbor = outgoing ? edge.to() : edge.from();
                if (visited.add(neighbor)) {
                    String signature = store.getNode(neighbor).map(GraphNode::signature).orElse(neighbor);
                    result.add(signature);
                    queue.add(neighbor);
                    levels.add(level + 1);
                }
            }
        }
        return result;
    }

    public KafkaFlow getKafkaFlow(String topic) {
        return new KafkaFlow(topic,
            store.getEdgesByType(topic, GraphEdge.EdgeType.PRODUCES, false).stream()
                .map(e -> store.getNode(e.from()).map(GraphNode::signature).orElse(e.from())).toList(),
            store.getEdgesByType(topic, GraphEdge.EdgeType.CONSUMES, false).stream()
                .map(e -> store.getNode(e.from()).map(GraphNode::signature).orElse(e.from())).toList());
    }

    public List<String> expandContext(List<String> seedFqns, int hops) {
        return expandContext(seedFqns, hops, Integer.MAX_VALUE);
    }

    public List<String> expandContext(List<String> seedFqns, int hops, int maxSignatures) {
        Set<String> visited = new LinkedHashSet<>(seedFqns);
        Set<String> expanded = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(seedFqns);
        for (int h = 0; h < hops; h++) {
            Set<String> next = new LinkedHashSet<>();
            for (String fqn : frontier) {
                store.getOutEdges(fqn).forEach(e -> next.add(e.to()));
                store.getInEdges(fqn).forEach(e -> next.add(e.from()));
            }
            next.removeAll(visited);
            visited.addAll(next);
            expanded.addAll(next);
            frontier = next;
            if (frontier.isEmpty()) break;
        }
        return expanded.stream()
            .limit(maxSignatures)
            .map(id -> store.getNode(id).map(GraphNode::signature).orElse(id))
            .toList();
    }

    public Optional<EndpointInfo> getEndpoint(String httpMethod, String path) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String nodeId = httpMethod.toUpperCase() + " " + normalizedPath;
        return store.getNode(nodeId).map(node -> {
            String handlerFqn = store.getInEdges(nodeId).stream()
                .filter(edge -> edge.type() == GraphEdge.EdgeType.EXPOSES)
                .map(GraphEdge::from)
                .findFirst()
                .orElse(null);
            return new EndpointInfo(httpMethod.toUpperCase(), normalizedPath, node.javadoc(), handlerFqn);
        });
    }

    public BeanGraph getBeanGraph(String name, int depth) {
        String rootId = store.getNodesByTag("BEAN").stream()
            .filter(id -> id.equals(name) || id.endsWith("." + name) || id.contains(name))
            .findFirst()
            .orElse(null);
        if (rootId == null) return new BeanGraph(name, List.of());

        List<BeanDep> dependencies = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(rootId));
        Deque<String> queue = new ArrayDeque<>(List.of(rootId));
        Map<String, Integer> depths = new HashMap<>();
        depths.put(rootId, 0);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depths.get(current);
            if (currentDepth >= depth) continue;

            for (GraphEdge edge : store.getEdgesByType(current, GraphEdge.EdgeType.DEPENDS_ON, true)) {
                String depId = edge.to();
                if (store.getNode(depId).isEmpty()) {
                    depId = store.getNodesByTag("BEAN").stream()
                        .filter(id -> id.equals(edge.to()) || id.endsWith("." + edge.to()))
                        .findFirst()
                        .orElse(edge.to());
                }
                if (visited.add(depId)) {
                    int nextDepth = currentDepth + 1;
                    String signature = store.getNode(depId).map(GraphNode::signature).orElse(depId);
                    dependencies.add(new BeanDep(depId, signature, nextDepth));
                    queue.add(depId);
                    depths.put(depId, nextDepth);
                }
            }
        }
        return new BeanGraph(rootId, dependencies);
    }

    public Set<String> getEndpointKeys(String repo) {
        Set<String> repoNodes = repo != null ? store.getNodesByTag("REPO:" + repo) : null;
        Set<String> result = new HashSet<>();
        for (String nodeId : store.getNodesByTag("ENDPOINT")) {
            if (repoNodes != null && !repoNodes.contains(nodeId)) continue;
            boolean implemented = store.getInEdges(nodeId).stream()
                .anyMatch(edge -> edge.type() == GraphEdge.EdgeType.EXPOSES);
            if (implemented) result.add(nodeId);
        }
        return result;
    }
}
