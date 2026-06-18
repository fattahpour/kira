package com.acme.airetrieval.retrieve.dto;

public record SymbolRef(
    String fqn,
    String snippet,
    String type,
    String path
) {}
