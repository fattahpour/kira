package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Domain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserTest {
    private final MarkdownParser parser = new MarkdownParser();

    @Test
    void parseHeadingSections() {
        var chunks = parser.parse("repo", "docs/readme.md", "sha", "# Install\n\nRun Maven.\n\n# Config\n\nEdit YAML.");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().id()).isEqualTo("docs/readme.md#install");
        assertThat(chunks.getFirst().domain()).isEqualTo(Domain.KNOWLEDGE);
        assertThat(chunks.getFirst().contentHash()).isNotBlank();
        assertThat(chunks.getFirst().section()).isNull();
        assertThat(chunks.get(1).title()).isEqualTo("Config");
    }

    @Test
    void parseNoHeadingFallsBackToBody() {
        var chunks = parser.parse("repo", "notes.md", "sha", "plain body");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().id()).isEqualTo("notes.md#body");
    }

    @Test
    void largeSection_isSplitIntoSubChunks() {
        StringBuilder sb = new StringBuilder("# Big Section\n\n");
        for (int i = 0; i < 25; i++) {
            sb.append("Paragraph ").append(i).append(": ")
                .append("a".repeat(180)).append("\n\n");
        }

        var chunks = parser.parse("repo", "big.md", "sha", sb.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> chunk.text().length() <= 4100);
        assertThat(chunks).allMatch(chunk -> "Big Section".equals(chunk.title()));
    }

    @Test
    void smallSection_remainsSingleChunk() {
        var chunks = parser.parse("repo", "small.md", "sha", "# Small\n\nJust a short paragraph.");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().title()).isEqualTo("Small");
    }

    @Test
    void hashReturns16Chars() {
        assertThat(MarkdownParser.hash("hello")).hasSize(16);
    }
}
