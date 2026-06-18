package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record SymbolListResult(List<SymbolRef> symbols, String error) {
    public static SymbolListResult ok(List<SymbolRef> symbols) {
        return new SymbolListResult(symbols, null);
    }

    public static SymbolListResult err(String message) {
        return new SymbolListResult(List.of(), message);
    }

    public boolean isError() {
        return error != null;
    }
}
