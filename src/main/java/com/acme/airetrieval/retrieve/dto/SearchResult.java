package com.acme.airetrieval.retrieve.dto;

import com.acme.airetrieval.index.model.SearchHit;
import java.util.List;

public record SearchResult(List<SearchHit> hits, String error) {
    public static SearchResult ok(List<SearchHit> hits) {
        return new SearchResult(hits, null);
    }

    public static SearchResult err(String message) {
        return new SearchResult(List.of(), message);
    }

    public boolean isError() {
        return error != null;
    }
}
