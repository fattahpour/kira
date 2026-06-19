package com.acme.airetrieval.config;

import com.acme.airetrieval.index.IndexMonitorService;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.BranchSyncScheduler;
import com.acme.airetrieval.ingest.CheckpointStore;
import com.acme.airetrieval.ingest.IndexActivityTracker;
import com.acme.airetrieval.observe.MetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserveConfig {
    @Bean
    public MetricsService metricsService(MeterRegistry registry) {
        return new MetricsService(registry);
    }

    @Bean
    public IndexMonitorService indexMonitorService(NrtLuceneSearcher searcher,
                                                   CheckpointStore checkpointStore,
                                                   BranchSyncScheduler syncScheduler,
                                                   IndexActivityTracker activityTracker,
                                                   ApplicationProps props) {
        return new IndexMonitorService(searcher, checkpointStore, syncScheduler, activityTracker, props);
    }
}
