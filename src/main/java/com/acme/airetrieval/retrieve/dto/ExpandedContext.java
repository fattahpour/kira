package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record ExpandedContext(List<String> seedFqns, int hops, List<String> signatures) {}
