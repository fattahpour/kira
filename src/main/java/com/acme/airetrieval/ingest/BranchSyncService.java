package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.RepoConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BranchSyncService {
    private final BranchResolver branchResolver;
    private final CheckpointStore checkpointStore;
    private final IndexService indexService;
    private final FullReindexService fullReindexService;

    public BranchSyncService(BranchResolver branchResolver, CheckpointStore checkpointStore,
                             IndexService indexService, FullReindexService fullReindexService) {
        this.branchResolver = branchResolver;
        this.checkpointStore = checkpointStore;
        this.indexService = indexService;
        this.fullReindexService = fullReindexService;
    }

    public Map<String, IndexService.IndexResult> sync(RepoConfig repo) throws Exception {
        List<String> branches = branchResolver.resolve(repo.path(), repo.branches());
        Map<String, IndexService.IndexResult> results = new LinkedHashMap<>();
        for (String branch : branches) {
            String headSha = branchResolver.headSha(repo.path(), branch);
            String fromSha = checkpointStore.get(repo.id(), branch).orElse(null);
            IndexService.IndexResult result;
            if (fromSha == null) {
                var r = fullReindexService.reindex(repo.id(), branch, repo.path(), headSha);
                result = new IndexService.IndexResult(r.indexed(), 0, r.skipped(), headSha);
            } else if (fromSha.equals(headSha)) {
                result = new IndexService.IndexResult(0, 0, 0, headSha);
            } else {
                result = indexService.indexIncremental(repo.path(), repo.id(), branch, fromSha, headSha);
            }
            checkpointStore.put(repo.id(), branch, headSha);
            results.put(branch, result);
        }
        checkpointStore.flush();
        return results;
    }
}
