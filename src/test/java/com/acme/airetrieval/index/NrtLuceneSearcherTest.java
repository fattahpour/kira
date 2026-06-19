package com.acme.airetrieval.index;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import com.acme.airetrieval.index.model.SearchFilter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NrtLuceneSearcherTest {
    @Test
    void seesCommittedDocsAfterRefresh() throws Exception {
        var dir = Files.createTempDirectory("nrt");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("id1", "repo", null, "p", Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(), "sha", "hash", "md", "document one", null));
            indexer.commit();
            searcher.maybeReopen();
            assertThat(searcher.bm25("document", null, 5)).hasSize(1);
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void findByFqn_returnsTextForExactFqnMatch() throws Exception {
        var dir = Files.createTempDirectory("nrt-fqn");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("m1", "repo", null, "Foo.java", Domain.CODE, "METHOD",
                "com.acme.Foo#bar()", null, null, List.of(), "sha", "hash", "java",
                "public void bar() { return; }", null));
            indexer.commit();
            searcher.maybeReopen();

            assertThat(searcher.findByFqn("com.acme.Foo#bar()")).isPresent();
            assertThat(searcher.findByFqn("com.acme.Foo#bar()").get()).contains("bar");
            assertThat(searcher.findByFqn("does.not.Exist#method()")).isEmpty();
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void countByFilter() throws Exception {
        var dir = Files.createTempDirectory("nrt-cnt");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("c1", "repo1", "main", "Foo.java", Domain.CODE, "CLASS",
                "Foo", null, null, List.of(), "sha1", "h1", "java", "class Foo {}", null));
            indexer.upsert(new Chunk("k1", "repo1", "main", "README.md", Domain.KNOWLEDGE, "MARKDOWN",
                null, "README", null, List.of(), "sha1", "h2", "md", "# Readme", null));
            indexer.commit();
            searcher.maybeReopen();

            assertThat(searcher.count(new SearchFilter("repo1", "CODE", null, null, "main"))).isEqualTo(1);
            assertThat(searcher.count(new SearchFilter("repo1", "KNOWLEDGE", null, null, "main"))).isEqualTo(1);
            assertThat(searcher.count(new SearchFilter("repo1", null, null, null, "main"))).isEqualTo(2);
            assertThat(searcher.count(new SearchFilter("repo2", null, null, null, null))).isEqualTo(0);
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void searchByNameFragment_returnsMatchingFqns() throws Exception {
        var dir = Files.createTempDirectory("nrt-frag");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("c1", "repo", null, "PaymentService.java", Domain.CODE, "CLASS",
                "com.acme.PaymentService", null, null, List.of(), "sha", "hash1", "java",
                "class PaymentService", null));
            indexer.upsert(new Chunk("m1", "repo", null, "PaymentService.java", Domain.CODE, "METHOD",
                "com.acme.PaymentService#settle(String)", null, null, List.of(), "sha", "hash2", "java",
                "public void settle(String id)", null));
            indexer.commit();
            searcher.maybeReopen();

            var results = searcher.searchByNameFragment("PaymentService", null, 10);
            assertThat(results).isNotEmpty();
            assertThat(results).anyMatch(hit -> hit.fqn() != null && hit.fqn().contains("PaymentService"));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void searchByNameFragment_filtersByType() throws Exception {
        var dir = Files.createTempDirectory("nrt-type");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("c1", "repo", null, "Foo.java", Domain.CODE, "CLASS",
                "com.acme.FooService", null, null, List.of(), "sha", "h1", "java", "class FooService", null));
            indexer.upsert(new Chunk("m1", "repo", null, "Foo.java", Domain.CODE, "METHOD",
                "com.acme.FooService#doIt()", null, null, List.of(), "sha", "h2", "java", "void doIt()", null));
            indexer.commit();
            searcher.maybeReopen();

            var classOnly = searcher.searchByNameFragment("FooService", "CLASS", 10);
            assertThat(classOnly).allMatch(hit -> "CLASS".equals(hit.type()));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void searchByNameFragment_includesNonCodeChunks() throws Exception {
        var dir = Files.createTempDirectory("nrt-domain");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("op1", "repo", null, "spec.yaml", Domain.KNOWLEDGE, "OPENAPI_OP",
                "POST /api/orders", null, null, List.of(), "sha", "h1", "openapi",
                "POST /api/orders create order", null));
            indexer.commit();
            searcher.maybeReopen();

            var results = searcher.searchByNameFragment("orders", null, 10);
            assertThat(results).isNotEmpty();
            assertThat(results).anyMatch(hit -> "POST /api/orders".equals(hit.fqn()));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void searchByNameFragment_lowercasePrefixMatchViaFqnSimple() throws Exception {
        var dir = Files.createTempDirectory("nrt-simple");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("c1", "repo", null, "PaymentService.java", Domain.CODE, "CLASS",
                "com.acme.PaymentService", null, null, List.of(), "sha", "h1", "java",
                "class PaymentService", null));
            indexer.upsert(new Chunk("m1", "repo", null, "OrderService.java", Domain.CODE, "CLASS",
                "com.acme.OrderService", null, null, List.of(), "sha", "h2", "java",
                "class OrderService", null));
            indexer.commit();
            searcher.maybeReopen();

            var results = searcher.searchByNameFragment("paymentservice", null, 10);
            assertThat(results).isNotEmpty();
            assertThat(results).anyMatch(hit -> "com.acme.PaymentService".equals(hit.fqn()));
            assertThat(results).noneMatch(hit -> "com.acme.OrderService".equals(hit.fqn()));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void findByTypeAndRepo_returnsOnlyMatchingTypeAndRepo() throws Exception {
        var dir = Files.createTempDirectory("nrt-tr");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("op1", "myrepo", null, "spec.yaml", Domain.KNOWLEDGE, "OPENAPI_OP",
                "POST /api/pay", null, null, List.of(), "sha", "h1", "openapi", "POST /api/pay\nPay endpoint", null));
            indexer.upsert(new Chunk("op2", "other", null, "spec.yaml", Domain.KNOWLEDGE, "OPENAPI_OP",
                "GET /api/items", null, null, List.of(), "sha", "h2", "openapi", "GET /api/items", null));
            indexer.upsert(new Chunk("c1", "myrepo", null, "Foo.java", Domain.CODE, "METHOD",
                "com.Foo#m()", null, null, List.of(), "sha", "h3", "java", "void m()", null));
            indexer.commit();
            searcher.maybeReopen();

            var results = searcher.findByTypeAndRepo("OPENAPI_OP", "myrepo", 50);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).fqn()).isEqualTo("POST /api/pay");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void symbol_fieldIsIndexedAndFilterable() throws Exception {
        var dir = Files.createTempDirectory("nrt-sym");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            indexer.upsert(new Chunk("c1", "repo", null, "Foo.java", Domain.CODE, "CLASS",
                "com.acme.FooService", null, null, List.of("@Service"), "sha", "h1", "java",
                "class FooService", null));
            indexer.upsert(new Chunk("c2", "repo", null, "Bar.java", Domain.CODE, "CLASS",
                "com.acme.BarService", null, null, List.of("@Repository"), "sha", "h2", "java",
                "class BarService", null));
            indexer.commit();
            searcher.maybeReopen();

            var filter = new SearchFilter(null, null, null, null, null, "@Service");
            var results = searcher.bm25("FooService BarService", filter, 10);
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().fqn()).isEqualTo("com.acme.FooService");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    void snippet_truncatesAtWordBoundary() throws Exception {
        var dir = Files.createTempDirectory("nrt-snip");
        try (var indexer = new LuceneIndexer(dir); var searcher = new NrtLuceneSearcher(indexer.getWriter())) {
            String text = "start " + "longword ".repeat(70) + "end";
            indexer.upsert(new Chunk("s1", "repo", null, "S.java", Domain.CODE, "METHOD",
                "com.S#m()", null, null, List.of(), "sha", "h1", "java", text, null));
            indexer.commit();
            searcher.maybeReopen();

            var results = searcher.bm25("start", null, 1);
            assertThat(results).hasSize(1);
            String snippet = results.getFirst().snippet();
            assertThat(snippet).doesNotEndWith("longwor...");
            assertThat(snippet).endsWith("...");
            assertThat(snippet.replace("...", "")).hasSizeLessThanOrEqualTo(500);
        } finally {
            cleanup(dir);
        }
    }

    private static void cleanup(java.nio.file.Path dir) throws Exception {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
