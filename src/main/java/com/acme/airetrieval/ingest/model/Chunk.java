package com.acme.airetrieval.ingest.model;

import java.util.List;

public record Chunk(
    String id,
    String repo,
    String branch,
    String path,
    Domain domain,
    String type,
    String fqn,
    String title,
    String section,
    List<String> symbols,
    String gitSha,
    String contentHash,
    String lang,
    String text,
    float[] vector
) {}
