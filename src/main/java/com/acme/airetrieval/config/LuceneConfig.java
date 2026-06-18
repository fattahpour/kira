package com.acme.airetrieval.config;

import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class LuceneConfig {
    @Bean(destroyMethod = "close")
    public LuceneIndexer luceneIndexer(ApplicationProps props) throws IOException {
        return new LuceneIndexer(props.indexDir());
    }

    @Bean(destroyMethod = "close")
    public NrtLuceneSearcher luceneSearcher(LuceneIndexer indexer) throws IOException {
        return new NrtLuceneSearcher(indexer.getWriter());
    }
}
