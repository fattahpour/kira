package com.acme.airetrieval.index;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.config.ApplicationProps.RepoConfig;
import com.acme.airetrieval.ingest.BranchSyncScheduler;
import com.acme.airetrieval.ingest.CheckpointStore;
import com.acme.airetrieval.ingest.IndexActivityTracker;
import com.acme.airetrieval.index.model.SearchFilter;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import com.acme.airetrieval.retrieve.dto.RepoIndexStats;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class IndexMonitorService {
    private final NrtLuceneSearcher searcher;
    private final CheckpointStore checkpointStore;
    private final BranchSyncScheduler syncScheduler;
    private final IndexActivityTracker activityTracker;
    private final ApplicationProps props;

    public IndexMonitorService(NrtLuceneSearcher searcher, CheckpointStore checkpointStore,
                               BranchSyncScheduler syncScheduler, IndexActivityTracker activityTracker,
                               ApplicationProps props) {
        this.searcher = searcher;
        this.checkpointStore = checkpointStore;
        this.syncScheduler = syncScheduler;
        this.activityTracker = activityTracker;
        this.props = props;
    }

    public IndexStatus buildStatus(String serverVersion) throws IOException {
        int total = searcher.numDocs();
        List<RepoIndexStats> repoStats = collectRepoStats();
        boolean anyIndexing = activityTracker.anyActive();
        return new IndexStatus(total, serverVersion, repoStats, anyIndexing);
    }

    private List<RepoIndexStats> collectRepoStats() throws IOException {
        List<RepoIndexStats> result = new ArrayList<>();
        List<RepoConfig> repos = props.repos();
        if (repos == null) return result;

        for (RepoConfig repo : repos) {
            List<String> branches = trackedBranches(repo);
            for (String branch : branches) {
                int codeDocs = searcher.count(new SearchFilter(repo.id(), "CODE", null, null, branch));
                int knowledgeDocs = searcher.count(new SearchFilter(repo.id(), "KNOWLEDGE", null, null, branch));
                int total = searcher.count(new SearchFilter(repo.id(), null, null, null, branch));
                String lastSha = checkpointStore.get(repo.id(), branch).orElse(null);
                Instant lastSyncAt = syncScheduler != null ? syncScheduler.getLastSync(repo.id()) : null;
                boolean indexing = activityTracker.isActive(repo.id(), branch);
                result.add(new RepoIndexStats(
                    repo.id(), branch, codeDocs, knowledgeDocs, total,
                    lastSha,
                    lastSyncAt != null ? lastSyncAt.toString() : null,
                    indexing));
            }
        }
        return result;
    }

    private static List<String> trackedBranches(RepoConfig repo) {
        if (repo.branches() == null) return List.of("main");
        List<String> tracked = repo.branches().tracked();
        return tracked == null || tracked.isEmpty() ? List.of("main") : tracked;
    }
}
