package com.acme.airetrieval.api.dto;

import com.acme.airetrieval.index.model.SearchHit;

import java.util.List;

public record SearchResponse(List<SearchHit> hits, int total) {}
