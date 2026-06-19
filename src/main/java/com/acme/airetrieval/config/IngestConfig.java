package com.acme.airetrieval.config;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.graph.GraphExtractor;
import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.BranchResolver;
import com.acme.airetrieval.ingest.BranchSyncService;
import com.acme.airetrieval.ingest.CheckpointStore;
import com.acme.airetrieval.ingest.FilterRegistry;
import com.acme.airetrieval.ingest.FullReindexService;
import com.acme.airetrieval.ingest.IndexActivityTracker;
import com.acme.airetrieval.ingest.IndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

@Configuration
public class IngestConfig {
    @Bean
    public FilterRegistry filterRegistry(ApplicationProps props) {
        return new FilterRegistry(props.accept(), props.repos());
    }

    @Bean
    public CheckpointStore checkpointStore(ApplicationProps props, ObjectMapper objectMapper) throws IOException {
        return new CheckpointStore(props.checkpointFile(), objectMapper);
    }

    @Bean
    public BranchResolver branchResolver() {
        return new BranchResolver();
    }

    @Bean
    public IndexActivityTracker indexActivityTracker() {
        return new IndexActivityTracker();
    }

    @Bean
    public IndexService indexService(LuceneIndexer indexer, NrtLuceneSearcher searcher,
                                     EmbeddingModel embeddingModel, GraphExtractor graphExtractor,
                                     FilterRegistry filterRegistry, ApplicationProps props) {
        return new IndexService(indexer, searcher, embeddingModel, graphExtractor,
            filterRegistry, props.respectGitignore());
    }

    @Bean
    public FullReindexService fullReindexService(IndexService indexService, NrtLuceneSearcher searcher,
                                                  ExecutorService executor, ApplicationProps props,
                                                  FilterRegistry filterRegistry,
                                                  IndexActivityTracker activityTracker) {
        return new FullReindexService(indexService, searcher, executor, props, filterRegistry, activityTracker);
    }

    @Bean
    public BranchSyncService branchSyncService(BranchResolver branchResolver, CheckpointStore checkpointStore,
                                               IndexService indexService, FullReindexService fullReindexService,
                                               IndexActivityTracker activityTracker) {
        return new BranchSyncService(branchResolver, checkpointStore, indexService, fullReindexService, activityTracker);
    }
}
