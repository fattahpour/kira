package com.acme.airetrieval.index;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.ingest.BranchSyncScheduler;
import com.acme.airetrieval.ingest.CheckpointStore;
import com.acme.airetrieval.ingest.IndexActivityTracker;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.model.Domain;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import com.acme.airetrieval.retrieve.dto.RepoIndexStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IndexMonitorServiceTest {

    @TempDir Path tempDir;
    LuceneIndexer indexer;
    NrtLuceneSearcher searcher;

    @BeforeEach
    void setUp() throws Exception {
        indexer = new LuceneIndexer(tempDir.resolve("idx"));
        searcher = new NrtLuceneSearcher(indexer.getWriter());
    }

    @AfterEach
    void tearDown() throws Exception {
        searcher.close();
        indexer.close();
    }

    @Test
    void buildsStatusWithPerRepoCounts() throws Exception {
        indexer.upsert(new Chunk("c1", "myrepo", "main", "Foo.java", Domain.CODE, "CLASS",
            "Foo", null, null, List.of(), "sha1", "h1", "java", "class Foo {}", null));
        indexer.upsert(new Chunk("c2", "myrepo", "main", "Bar.java", Domain.CODE, "CLASS",
            "Bar", null, null, List.of(), "sha1", "h2", "java", "class Bar {}", null));
        indexer.upsert(new Chunk("k1", "myrepo", "main", "README.md", Domain.KNOWLEDGE, "MARKDOWN",
            null, "Readme", null, List.of(), "sha1", "h3", "md", "# Hello", null));
        indexer.commit();
        searcher.maybeReopen();

        CheckpointStore checkpoint = mock(CheckpointStore.class);
        when(checkpoint.get("myrepo", "main")).thenReturn(Optional.of("abc123"));

        BranchSyncScheduler scheduler = mock(BranchSyncScheduler.class);
        when(scheduler.getLastSync("myrepo")).thenReturn(null);

        IndexActivityTracker tracker = new IndexActivityTracker();

        ApplicationProps props = buildProps("myrepo", "main");

        IndexMonitorService monitor = new IndexMonitorService(searcher, checkpoint, scheduler, tracker, props);
        IndexStatus status = monitor.buildStatus("0.1.0");

        assertThat(status.totalDocs()).isEqualTo(3);
        assertThat(status.repos()).hasSize(1);

        RepoIndexStats stats = status.repos().get(0);
        assertThat(stats.repo()).isEqualTo("myrepo");
        assertThat(stats.branch()).isEqualTo("main");
        assertThat(stats.codeDocs()).isEqualTo(2);
        assertThat(stats.knowledgeDocs()).isEqualTo(1);
        assertThat(stats.totalDocs()).isEqualTo(3);
        assertThat(stats.lastSha()).isEqualTo("abc123");
        assertThat(stats.lastSyncAt()).isNull();
        assertThat(stats.indexing()).isFalse();
    }

    @Test
    void activeRepoShownAsIndexing() throws Exception {
        indexer.commit();
        searcher.maybeReopen();

        CheckpointStore checkpoint = mock(CheckpointStore.class);
        when(checkpoint.get(any(), any())).thenReturn(Optional.empty());
        BranchSyncScheduler scheduler = mock(BranchSyncScheduler.class);

        IndexActivityTracker tracker = new IndexActivityTracker();
        tracker.start("myrepo", "main");

        ApplicationProps props = buildProps("myrepo", "main");
        IndexMonitorService monitor = new IndexMonitorService(searcher, checkpoint, scheduler, tracker, props);
        IndexStatus status = monitor.buildStatus("0.1.0");

        assertThat(status.anyIndexing()).isTrue();
        assertThat(status.repos().get(0).indexing()).isTrue();
    }

    private ApplicationProps buildProps(String repoId, String branch) {
        var branchCfg = new ApplicationProps.BranchConfig(
            ApplicationProps.BranchMode.SINGLE, List.of(branch));
        var repoConfig = new ApplicationProps.RepoConfig(
            repoId, tempDir, branchCfg, ApplicationProps.AutoSyncConfig.disabled(),
            ApplicationProps.AcceptConfig.acceptAll());
        return new ApplicationProps(
            tempDir, tempDir.resolve("idx"), tempDir.resolve("cp.json"),
            tempDir.resolve("models"), 20, 10, 50, 500,
            null, null,
            new ApplicationProps.TokenBudgetConfig(4000, 4),
            new ApplicationProps.Executor(2),
            new ApplicationProps.Graph("jgrapht", null),
            new ApplicationProps.FullReindex(10, 4),
            ApplicationProps.AcceptConfig.acceptAll(),
            List.of(repoConfig),
            true);
    }
}
