package com.acme.airetrieval.graph.model;

public record GraphEdge(String from, String to, EdgeType type) {
    public enum EdgeType {
        DECLARES, CALLS, IMPLEMENTS, EXTENDS, EXPOSES, PRODUCES, CONSUMES,
        DEPENDS_ON, SPECIFIES, DESCRIBES, MENTIONS
    }
}
