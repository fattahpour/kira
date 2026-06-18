package com.acme.airetrieval.graph;

import com.acme.airetrieval.graph.model.GraphEvent;
import java.util.List;

public class GraphExtractor {
    private final GraphStore store;

    public GraphExtractor(GraphStore store) {
        this.store = store;
    }

    public void apply(List<GraphEvent> events) {
        events.forEach(store::applyEvent);
    }
}
