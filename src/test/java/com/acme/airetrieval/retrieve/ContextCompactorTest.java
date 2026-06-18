package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompactorTest {
    @Test
    void compactsCodeAndDocsWithinBudget() {
        var compactor = new ContextCompactor(new TokenBudget(4));
        var code = new SearchHit("a", "repo", "A.java", Domain.CODE, "METHOD", "A#a()", null, null, "void a() {}", 1);
        var doc = new SearchHit("b", "repo", "b.md", Domain.KNOWLEDGE, "MD_SECTION", null, "Guide", "Install", "Run Maven", 1);
        String context = compactor.compact(List.of(code, doc), 50);
        assertThat(context).contains("A#a()");
        assertThat(context).contains("Guide");
    }

    @Test
    void compact_reservesMinimumForEachHit() {
        var compactor = new ContextCompactor(new TokenBudget(4));
        String longText = "x ".repeat(200);
        var big = new SearchHit("big", "repo", "big.java", Domain.CODE, "METHOD", "Big#m()", null, null, longText, 2);
        var small = new SearchHit("small", "repo", "s.java", Domain.CODE, "METHOD", "Small#m()", null, null, "short text", 1);

        String context = compactor.compact(List.of(big, small), 100);

        assertThat(context).contains("Big#m()");
        assertThat(context).contains("Small#m()");
    }
}
