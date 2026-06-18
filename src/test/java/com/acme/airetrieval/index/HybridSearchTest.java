package com.acme.airetrieval.index;

import com.acme.airetrieval.embed.DeterministicEmbeddingModel;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridSearchTest {
    @Test
    void candidateK_isStoredAsInstanceField() throws Exception {
        var dir = Files.createTempDirectory("hs-ck");
        var embedding = new DeterministicEmbeddingModel(32);
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            var field = HybridSearch.class.getDeclaredField("candidateK");
            field.setAccessible(true);
            assertThat(field.get(new HybridSearch(searcher, embedding, 30))).isEqualTo(30);
            assertThat(field.get(new HybridSearch(searcher, embedding))).isEqualTo(50);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void fusesBm25AndKnnResults() throws Exception {
        var dir = Files.createTempDirectory("hybrid");
        var embedding = new DeterministicEmbeddingModel(32);
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            String text = "kafka stream processing";
            indexer.upsert(new Chunk("kafka", "repo", null, "k.md", Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(), "sha", "hash1", "md", text, embedding.embed(text)));
            indexer.upsert(new Chunk("maven", "repo", null, "m.md", Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(), "sha", "hash2", "md", "maven build tool", embedding.embed("maven build tool")));
            indexer.commit();
            searcher.maybeReopen();
            assertThat(new HybridSearch(searcher, embedding).search("kafka stream", null, 5).getFirst().id()).isEqualTo("kafka");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
