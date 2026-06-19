package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record IndexStatus(
    int totalDocs,
    String serverVersion,
    List<RepoIndexStats> repos,
    boolean anyIndexing
) {
    public static IndexStatus simple(int totalDocs, String serverVersion) {
        return new IndexStatus(totalDocs, serverVersion, null, false);
    }
}
