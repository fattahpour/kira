package com.acme.airetrieval.retrieve;

public final class TokenBudget {
    private final int charsPerToken;

    public TokenBudget(int charsPerToken) {
        this.charsPerToken = Math.max(1, charsPerToken);
    }

    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil((double) text.length() / charsPerToken));
    }

    public boolean fits(String text, int budgetTokens) {
        return estimate(text) <= budgetTokens;
    }

    public String truncateToFit(String text, int budgetTokens) {
        if (text == null) return "";
        int maxChars = Math.max(0, budgetTokens * charsPerToken);
        if (text.length() <= maxChars) return text;
        int boundary = text.lastIndexOf(' ', maxChars);
        return text.substring(0, boundary > 0 ? boundary : maxChars);
    }
}
