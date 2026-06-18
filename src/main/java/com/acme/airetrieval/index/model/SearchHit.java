package com.acme.airetrieval.index.model;

import com.acme.airetrieval.ingest.model.Domain;
import org.apache.lucene.document.Document;

public record SearchHit(
    String id,
    String repo,
    String path,
    Domain domain,
    String type,
    String fqn,
    String title,
    String section,
    String snippet,
    float score
) {
    public static SearchHit fromDocument(Document doc, float score) {
        String domain = doc.get("domain");
        return new SearchHit(
            doc.get("id"),
            doc.get("repo"),
            doc.get("path"),
            domain == null ? null : Domain.valueOf(domain),
            doc.get("type"),
            doc.get("fqn"),
            doc.get("title"),
            doc.get("section"),
            snippet(doc.get("text")),
            score
        );
    }

    private static String snippet(String text) {
        if (text == null) return "";
        int limit = 500;
        if (text.length() <= limit) return text;
        int boundary = text.lastIndexOf(' ', limit);
        return text.substring(0, boundary > 0 ? boundary : limit) + "...";
    }
}
