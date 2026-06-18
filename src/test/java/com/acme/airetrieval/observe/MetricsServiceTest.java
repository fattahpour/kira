package com.acme.airetrieval.observe;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {
    @Test
    void allMetricsRegistered() {
        var registry = new SimpleMeterRegistry();
        var service = new MetricsService(registry);

        service.incrementIndexedChunks("repo1", 5);
        service.recordSearchRequest("hybrid");
        service.recordTokensReturned(1234);

        assertThat(service.searchLatencyTimer()).isNotNull();
        assertThat(service.embedLatencyTimer()).isNotNull();
        assertThat(service.rerankerLatencyTimer()).isNotNull();
        assertThat(registry.counter("kira.index.chunks", "repo", "repo1").count()).isEqualTo(5.0);
        assertThat(registry.counter("kira.search.requests", "mode", "hybrid").count()).isEqualTo(1.0);
        assertThat(registry.find("kira.tokens.returned.last").gauge().value()).isEqualTo(1234.0);
    }
}
