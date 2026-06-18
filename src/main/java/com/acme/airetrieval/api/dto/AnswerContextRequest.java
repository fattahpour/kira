package com.acme.airetrieval.api.dto;

public record AnswerContextRequest(String query, String repo, String domain, String type, Integer budgetTokens) {}
