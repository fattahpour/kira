package com.acme.airetrieval.ingest.parser;

import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class MarkdownParser {
    private static final Parser PARSER = Parser.builder().build();
    private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();
    static final int MAX_SECTION_CHARS = 4000;

    public List<Chunk> parse(String repo, String path, String gitSha, String content) {
        Node document = PARSER.parse(content == null ? "" : content);
        List<Section> sections = split(document);
        List<Chunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            String text = section.text().trim();
            if (text.isBlank()) continue;
            chunks.addAll(toChunks(repo, path, gitSha, section.heading(), text));
        }
        if (chunks.isEmpty()) {
            String text = TEXT.render(document).trim();
            chunks.add(new Chunk(path + "#body", repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, null, null, List.of(), gitSha, hash(text), "markdown", text, null));
        }
        return chunks;
    }

    private static List<Chunk> toChunks(String repo, String path, String gitSha, String heading, String text) {
        String slug = heading == null ? "body" : slugify(heading);
        if (text.length() <= MAX_SECTION_CHARS) {
            return List.of(new Chunk(path + "#" + slug, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
                null, heading, null, List.of(), gitSha, hash(text), "markdown", text, null));
        }

        List<Chunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int part = 0;
        for (String paragraph : text.split("\\n\\n+")) {
            if (current.length() + paragraph.length() + 2 > MAX_SECTION_CHARS && !current.isEmpty()) {
                part = addMarkdownChunk(result, repo, path, gitSha, heading, slug, current, part);
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(paragraph);
        }
        if (!current.isEmpty()) {
            addMarkdownChunk(result, repo, path, gitSha, heading, slug, current, part);
        }
        return result;
    }

    private static int addMarkdownChunk(List<Chunk> result, String repo, String path, String gitSha,
                                        String heading, String slug, StringBuilder current, int part) {
        String chunkText = current.toString().trim();
        String id = path + "#" + slug + (part > 0 ? "-" + part : "");
        result.add(new Chunk(id, repo, null, path, Domain.KNOWLEDGE, "MD_SECTION",
            null, heading, null, List.of(), gitSha, hash(chunkText), "markdown", chunkText, null));
        current.setLength(0);
        return part + 1;
    }

    private static List<Section> split(Node document) {
        List<Section> sections = new ArrayList<>();
        String heading = null;
        List<Node> current = new ArrayList<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading) {
                if (heading != null || !current.isEmpty()) {
                    sections.add(new Section(heading, render(current)));
                }
                heading = TEXT.render(child).trim();
                current = new ArrayList<>();
            } else {
                current.add(child);
            }
        }
        if (heading != null || !current.isEmpty()) {
            sections.add(new Section(heading, render(current)));
        }
        return sections;
    }

    private static String render(List<Node> nodes) {
        StringBuilder out = new StringBuilder();
        for (Node node : nodes) out.append(TEXT.render(node)).append('\n');
        return out.toString().trim();
    }

    public static String slugify(String heading) {
        if (heading == null || heading.isBlank()) return "body";
        return heading.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Section(String heading, String text) {}
}
