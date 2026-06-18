package com.acme.airetrieval.retrieve;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {
    private final TokenBudget budget = new TokenBudget(4);

    @Test
    void estimatesAndTruncates() {
        assertThat(budget.estimate("1234")).isEqualTo(1);
        assertThat(budget.estimate("12345")).isEqualTo(2);
        assertThat(budget.fits("abcd", 1)).isTrue();
        assertThat(budget.truncateToFit("word1 word2 word3", 3).length()).isLessThanOrEqualTo(12);
    }
}
