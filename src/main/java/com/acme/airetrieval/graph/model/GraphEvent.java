package com.acme.airetrieval.graph.model;

import java.util.Set;

public sealed interface GraphEvent {
    record NodeEvent(String id, String label, Set<String> tags, String signature, String javadoc) implements GraphEvent {}
    record EdgeEvent(String from, String to, GraphEdge.EdgeType type) implements GraphEvent {}
    record TagEvent(String id, String tag) implements GraphEvent {}
}
