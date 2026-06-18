package com.acme.airetrieval.api.dto;

public record IndexRequest(String repo, String repoDir, String path, String gitSha,
                           String fromSha, String toSha, String branch) {}
