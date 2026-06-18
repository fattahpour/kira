package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.apache.tika.Tika;

import java.nio.file.Path;
import java.util.List;

public final class DocumentParser {
    private final Tika tika = new Tika();

    public List<Chunk> parse(String repo, String path, String gitSha, Path file) throws Exception {
        String text = tika.parseToString(file);
        return parseText(repo, path, gitSha, text);
    }

    public List<Chunk> parseText(String repo, String path, String gitSha, String text) {
        String clean = text == null ? "" : text.strip();
        return List.of(new Chunk(path + "#body", repo, null, path, Domain.KNOWLEDGE, "DOCUMENT",
            null, null, null, List.of(), gitSha, MarkdownParser.hash(clean), extension(path), clean, null));
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    }
}
