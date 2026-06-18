package com.acme.airetrieval.ingest;

public final class SourceClassifier {
    public SourceType classify(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".java")) return SourceType.JAVA;
        if (p.endsWith(".md") || p.endsWith(".markdown")) return SourceType.MARKDOWN;
        if (p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".json")) return SourceType.API_SPEC;
        if (p.endsWith(".pdf") || p.endsWith(".docx") || p.endsWith(".html") || p.endsWith(".txt")) return SourceType.DOCUMENT;
        return SourceType.IGNORE;
    }

    public enum SourceType { JAVA, MARKDOWN, DOCUMENT, API_SPEC, IGNORE }
}
