package com.acme.airetrieval.ingest;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IndexActivityTrackerTest {

    @Test
    void noneActiveByDefault() {
        IndexActivityTracker t = new IndexActivityTracker();
        assertThat(t.anyActive()).isFalse();
        assertThat(t.isActive("repo1", "main")).isFalse();
    }

    @Test
    void startMakesRepoActive() {
        IndexActivityTracker t = new IndexActivityTracker();
        t.start("repo1", "main");
        assertThat(t.isActive("repo1", "main")).isTrue();
        assertThat(t.anyActive()).isTrue();
    }

    @Test
    void stopMakesRepoInactive() {
        IndexActivityTracker t = new IndexActivityTracker();
        t.start("repo1", "main");
        t.stop("repo1", "main");
        assertThat(t.isActive("repo1", "main")).isFalse();
        assertThat(t.anyActive()).isFalse();
    }

    @Test
    void nullBranchTreatedAsNoBranch() {
        IndexActivityTracker t = new IndexActivityTracker();
        t.start("repo1", null);
        assertThat(t.isActive("repo1", null)).isTrue();
        t.stop("repo1", null);
        assertThat(t.isActive("repo1", null)).isFalse();
    }

    @Test
    void activeReposReturnsCurrentlyActive() {
        IndexActivityTracker t = new IndexActivityTracker();
        t.start("repo1", "main");
        t.start("repo2", "dev");
        assertThat(t.activeKeys()).containsExactlyInAnyOrder("repo1:main", "repo2:dev");
    }
}
