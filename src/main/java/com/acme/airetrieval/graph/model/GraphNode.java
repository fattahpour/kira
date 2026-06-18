package com.acme.airetrieval.graph.model;

import java.util.Set;

public record GraphNode(String id, String label, Set<String> tags, String signature, String javadoc) {}
