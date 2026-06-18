package com.acme.airetrieval.api.dto;

public record SearchRequest(String query, String repo, String domain, String type,
                            String path, String branch, Integer k, String mode) {}
