package com.acme.airetrieval.index;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class LuceneIndexerTest {
    @TempDir Path tmp;

    @Test
    void deleteByPath_removesAllChunksForThatPath() throws Exception {
        try (var indexer = new LuceneIndexer(tmp)) {
            indexer.upsert(new Chunk("a/Foo.java#method1", "repo", null, "a/Foo.java", Domain.CODE, "METHOD",
                "com.Foo#method1", null, null, List.of(), "sha1", "h1", "java", "public void method1(){}", null));
            indexer.upsert(new Chunk("a/Foo.java#method2", "repo", null, "a/Foo.java", Domain.CODE, "METHOD",
                "com.Foo#method2", null, null, List.of(), "sha1", "h2", "java", "public void method2(){}", null));
            indexer.upsert(new Chunk("b/Bar.java#method3", "repo", null, "b/Bar.java", Domain.CODE, "METHOD",
                "com.Bar#method3", null, null, List.of(), "sha1", "h3", "java", "public void method3(){}", null));
            indexer.commit();
            indexer.deleteByPath("a/Foo.java");
            indexer.commit();
        }
    }
}
