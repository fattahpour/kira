package com.acme.airetrieval.index.model;

public record SearchFilter(String repo, String domain, String type, String path, String branch, String symbol) {
    public SearchFilter(String repo, String domain, String type, String path, String branch) {
        this(repo, domain, type, path, branch, null);
    }

    public SearchFilter(String repo, String domain, String type, String path) {
        this(repo, domain, type, path, null, null);
    }
}
