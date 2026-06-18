package com.acme.airetrieval.config;

import com.acme.airetrieval.graph.CodeGraphStore;
import com.acme.airetrieval.graph.GraphExtractor;
import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.graph.GraphStore;
import com.acme.airetrieval.graph.KuzuGraphStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class GraphConfig {
    @Bean
    @Primary
    public GraphStore graphStore(ApplicationProps props) {
        if ("kuzu".equalsIgnoreCase(props.graph().engine())) {
            return new KuzuGraphStore(props.graph().kuzuDir());
        }
        return new CodeGraphStore();
    }

    @Bean
    public GraphExtractor graphExtractor(GraphStore graphStore) {
        return new GraphExtractor(graphStore);
    }

    @Bean
    public GraphQueries graphQueries(GraphStore graphStore) {
        return new GraphQueries(graphStore);
    }
}
