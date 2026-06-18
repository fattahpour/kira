package com.acme.airetrieval.index;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HybridSearch {
    private static final int RRF_K = 60;
    private final NrtLuceneSearcher searcher;
    private final EmbeddingModel embeddingModel;
    private final int candidateK;

    public HybridSearch(NrtLuceneSearcher searcher, EmbeddingModel embeddingModel) {
        this(searcher, embeddingModel, 50);
    }

    public HybridSearch(NrtLuceneSearcher searcher, EmbeddingModel embeddingModel, int candidateK) {
        this.searcher = searcher;
        this.embeddingModel = embeddingModel;
        this.candidateK = candidateK;
    }

    public List<SearchHit> search(String query, SearchFilter filter, int k) throws Exception {
        List<SearchHit> bm25 = searcher.bm25(query, filter, candidateK);
        List<SearchHit> knn = searcher.knn(embeddingModel.embed(query), filter, candidateK);
        return fuse(bm25, knn).stream().limit(k).toList();
    }

    public List<SearchHit> bm25Only(String query, SearchFilter filter, int k) throws Exception {
        return searcher.bm25(query, filter, k);
    }

    private static List<SearchHit> fuse(List<SearchHit> left, List<SearchHit> right) {
        Map<String, Ranked> scores = new LinkedHashMap<>();
        add(scores, left);
        add(scores, right);
        return scores.values().stream()
            .sorted((a, b) -> Double.compare(b.score, a.score))
            .map(Ranked::hit)
            .toList();
    }

    private static void add(Map<String, Ranked> scores, List<SearchHit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            int rank = i + 1;
            scores.compute(hit.id(), (id, existing) -> {
                double score = 1.0d / (RRF_K + rank);
                if (existing == null) return new Ranked(hit, score);
                existing.score += score;
                return existing;
            });
        }
    }

    private static final class Ranked {
        private final SearchHit hit;
        private double score;
        private Ranked(SearchHit hit, double score) { this.hit = hit; this.score = score; }
        private SearchHit hit() { return hit; }
    }
}
