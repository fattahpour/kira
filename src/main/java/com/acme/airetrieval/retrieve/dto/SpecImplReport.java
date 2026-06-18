package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SpecImplReport(
    String repo,
    List<String> unimplemented,
    List<String> undocumented,
    List<String> matched,
    int total,
    String error
) {}
