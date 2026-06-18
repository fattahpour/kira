package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SymbolView(
    String fqn,
    String signature,
    String javadoc,
    String body,
    List<String> callerSignatures,
    List<String> calleeSignatures
) {}
