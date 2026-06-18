package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.embed.Reranker;
import com.acme.airetrieval.graph.GraphQueries;
import com.acme.airetrieval.index.HybridSearch;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.retrieve.dto.SpecImplReport;
import com.acme.airetrieval.retrieve.dto.SymbolRef;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class RetrievalOrchestrator {
    private final NrtLuceneSearcher searcher;
    private final HybridSearch hybridSearch;
    private final Reranker reranker;
    private final ContextCompactor compactor;
    private final ApplicationProps props;
    private final GraphQueries graphQueries;

    public RetrievalOrchestrator(NrtLuceneSearcher searcher, EmbeddingModel embeddingModel,
                                 Reranker reranker, ContextCompactor compactor,
                                 ApplicationProps props, GraphQueries graphQueries) {
        this.searcher = searcher;
        this.hybridSearch = new HybridSearch(searcher, embeddingModel, props.candidateK());
        this.reranker = reranker;
        this.compactor = compactor;
        this.props = props;
        this.graphQueries = graphQueries;
    }

    public int indexDocCount() throws IOException {
        return searcher.numDocs();
    }

    public List<SearchHit> hybrid(String query, SearchFilter filter, int k) throws Exception {
        return hybridSearch.search(query, filter, k);
    }

    public List<SearchHit> bm25Only(String query, SearchFilter filter, int k) throws Exception {
        return hybridSearch.bm25Only(query, filter, k);
    }

    public List<SearchHit> hybridRerank(String query, SearchFilter filter, int k) throws Exception {
        List<SearchHit> candidates = hybridSearch.search(query, filter, Math.max(k, props.candidateK()));
        if (reranker == null || candidates.isEmpty()) return candidates.stream().limit(k).toList();
        List<ScoredHit> scored = new java.util.ArrayList<>();
        for (SearchHit hit : candidates) {
            scored.add(new ScoredHit(hit, reranker.score(query, hit.snippet())));
        }
        return scored.stream()
            .sorted((a, b) -> Float.compare(b.score, a.score))
            .limit(k)
            .map(ScoredHit::hit)
            .toList();
    }

    public String answerContext(String query, SearchFilter filter, int budgetTokens) throws Exception {
        int budget = budgetTokens <= 0 ? props.tokenBudget().defaultBudgetTokens() : budgetTokens;
        return compactor.compact(hybridRerank(query, filter, props.defaultSearchK()), budget);
    }

    public Optional<String> findBodyByFqn(String fqn) throws IOException {
        return searcher.findByFqn(fqn);
    }

    public List<SymbolRef> findSymbols(String partial, String type, int k) throws IOException {
        return searcher.searchByNameFragment(partial, type, k).stream()
            .filter(hit -> hit.fqn() != null)
            .map(hit -> new SymbolRef(hit.fqn(), hit.snippet(), hit.type(), hit.path()))
            .toList();
    }

    public List<SearchHit> getDesignDocs(String fqn) throws Exception {
        return hybridRerank(simpleName(fqn), new SearchFilter(null, "KNOWLEDGE", null, null), 5);
    }

    public List<SearchHit> getCodeForDoc(String docId) throws Exception {
        String query = searcher.findTitleById(docId).orElse(docId);
        return hybridRerank(query, new SearchFilter(null, "CODE", null, null), 5);
    }

    public SpecImplReport checkSpecVsImpl(String repo) throws IOException {
        List<SearchHit> specHits = searcher.findByTypeAndRepo("OPENAPI_OP", repo, props.specMaxOps());
        int total = specHits.size();
        Set<String> specKeys = specHits.stream()
            .map(SearchHit::fqn)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Set<String> implKeys = graphQueries.getEndpointKeys(repo);

        List<String> unimplemented = specKeys.stream().filter(key -> !implKeys.contains(key)).sorted().toList();
        List<String> undocumented = implKeys.stream().filter(key -> !specKeys.contains(key)).sorted().toList();
        List<String> matched = specKeys.stream().filter(implKeys::contains).sorted().toList();
        return new SpecImplReport(repo, unimplemented, undocumented, matched, total, null);
    }

    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isBlank()) return fqn;
        int sep = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('#'));
        String simple = sep >= 0 ? fqn.substring(sep + 1) : fqn;
        int paren = simple.indexOf('(');
        return paren >= 0 ? simple.substring(0, paren) : simple;
    }

    private record ScoredHit(SearchHit hit, float score) {}
}
