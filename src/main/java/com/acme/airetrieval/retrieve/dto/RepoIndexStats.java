package com.acme.airetrieval.retrieve.dto;

public record RepoIndexStats(
    String repo,
    String branch,
    int codeDocs,
    int knowledgeDocs,
    int totalDocs,
    String lastSha,
    String lastSyncAt,
    boolean indexing
) {}
