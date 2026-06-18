package com.acme.airetrieval.retrieve;

import com.acme.airetrieval.index.model.SearchHit;
import com.acme.airetrieval.ingest.model.Domain;

import java.util.List;

public class ContextCompactor {
    private static final int MIN_TOKENS_PER_HIT = 20;
    private final TokenBudget tokenBudget;

    public ContextCompactor(TokenBudget tokenBudget) {
        this.tokenBudget = tokenBudget;
    }

    public String compact(List<SearchHit> hits, int budgetTokens) {
        if (hits.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            int remaining = budgetTokens - used;
            int futureHits = hits.size() - i - 1;
            int reserved = MIN_TOKENS_PER_HIT * futureHits;
            int available = Math.max(MIN_TOKENS_PER_HIT, remaining - reserved);
            if (available <= 0) break;
            String block = block(hit);
            int cost = tokenBudget.estimate(block);
            if (cost > available) {
                block = tokenBudget.truncateToFit(block, available);
                cost = tokenBudget.estimate(block);
            }
            if (block.isBlank()) continue;
            out.append(block).append("\n\n");
            used += cost;
            if (used >= budgetTokens) break;
        }
        return out.toString().stripTrailing();
    }

    private static String block(SearchHit hit) {
        if (hit.domain() == Domain.CODE) {
            return "// " + (hit.fqn() == null ? hit.id() : hit.fqn()) + "\n" + hit.snippet();
        }
        String title = hit.title() == null ? hit.path() : hit.title();
        String section = hit.section() == null ? "" : " > " + hit.section();
        return "## " + title + section + "\n" + hit.snippet();
    }
}
