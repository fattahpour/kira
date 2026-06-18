package com.acme.airetrieval.observe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsService {
    private final MeterRegistry registry;
    private final AtomicLong lastTokensReturned = new AtomicLong(0);

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("kira.tokens.returned.last", lastTokensReturned, AtomicLong::get);
    }

    public void incrementIndexedChunks(String repo, int count) {
        registry.counter("kira.index.chunks", "repo", repo == null ? "unknown" : repo).increment(count);
    }

    public void recordSearchRequest(String mode) {
        registry.counter("kira.search.requests", "mode", mode == null ? "hybrid" : mode).increment();
    }

    public void recordTokensReturned(long tokens) {
        lastTokensReturned.set(tokens);
    }

    public Timer searchLatencyTimer() {
        return registry.timer("kira.search.latency");
    }

    public Timer embedLatencyTimer() {
        return registry.timer("kira.embed.latency");
    }

    public Timer rerankerLatencyTimer() {
        return registry.timer("kira.reranker.latency");
    }
}
