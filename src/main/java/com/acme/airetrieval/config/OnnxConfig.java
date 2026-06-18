package com.acme.airetrieval.config;

import com.acme.airetrieval.embed.DeterministicEmbeddingModel;
import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.embed.OnnxEmbeddingModel;
import com.acme.airetrieval.embed.Reranker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;

@Configuration
public class OnnxConfig {
    @Bean(destroyMethod = "close")
    public EmbeddingModel embeddingModel(ApplicationProps props) throws Exception {
        var emb = props.embedding();
        if (Files.exists(emb.modelPath()) && Files.exists(emb.tokenizerPath())) {
            return new OnnxEmbeddingModel(emb.modelPath(), emb.tokenizerPath());
        }
        return new DeterministicEmbeddingModel(emb.dim());
    }

    @Bean(destroyMethod = "close")
    public Reranker reranker(ApplicationProps props, ObjectProvider<EmbeddingModel> ignored) throws Exception {
        var r = props.reranker();
        if (r.enabled() && Files.exists(r.modelPath()) && Files.exists(r.tokenizerPath())) {
            return new Reranker(r.modelPath(), r.tokenizerPath());
        }
        return null;
    }
}
