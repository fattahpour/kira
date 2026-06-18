package com.acme.airetrieval.config;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.embed.Reranker;
import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.retrieve.ContextCompactor;
import com.acme.airetrieval.retrieve.RetrievalOrchestrator;
import com.acme.airetrieval.retrieve.TokenBudget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrievalConfig {
    @Bean
    public TokenBudget tokenBudget(ApplicationProps props) {
        return new TokenBudget(props.tokenBudget().charsPerToken());
    }

    @Bean
    public ContextCompactor contextCompactor(TokenBudget tokenBudget) {
        return new ContextCompactor(tokenBudget);
    }

    @Bean
    public RetrievalOrchestrator retrievalOrchestrator(NrtLuceneSearcher searcher,
                                                       EmbeddingModel embeddingModel,
                                                       ObjectProvider<Reranker> rerankerProvider,
                                                       ContextCompactor compactor,
                                                       ApplicationProps props,
                                                       GraphQueries graphQueries) {
        return new RetrievalOrchestrator(searcher, embeddingModel, rerankerProvider.getIfAvailable(),
            compactor, props, graphQueries);
    }
}
