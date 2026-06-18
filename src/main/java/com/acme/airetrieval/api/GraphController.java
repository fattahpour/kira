package com.acme.airetrieval.api;

import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.retrieve.dto.KafkaFlow;
import com.acme.airetrieval.retrieve.dto.SymbolView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {
    private final GraphQueries queries;

    public GraphController(GraphQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/symbol")
    public SymbolView symbol(@RequestParam String fqn) {
        return queries.getSymbolView(fqn);
    }

    @GetMapping("/callers")
    public List<String> callers(@RequestParam String fqn, @RequestParam(defaultValue = "1") int depth) {
        return queries.getCallers(fqn, depth);
    }

    @GetMapping("/callees")
    public List<String> callees(@RequestParam String fqn, @RequestParam(defaultValue = "1") int depth) {
        return queries.getCallees(fqn, depth);
    }

    @GetMapping("/kafka/{topic}")
    public KafkaFlow kafka(@PathVariable String topic) {
        return queries.getKafkaFlow(topic);
    }
}
