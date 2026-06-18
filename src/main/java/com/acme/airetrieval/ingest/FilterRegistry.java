package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.AcceptConfig;
import com.acme.airetrieval.config.ApplicationProps.RepoConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FilterRegistry {
    private final FileAcceptanceFilter defaultFilter;
    private final Map<String, FileAcceptanceFilter> byRepo;

    public FilterRegistry(AcceptConfig defaultConfig, List<RepoConfig> repos) {
        AcceptConfig eff = defaultConfig != null ? defaultConfig : AcceptConfig.defaults();
        this.defaultFilter = new FileAcceptanceFilter(eff);
        this.byRepo = new HashMap<>();
        if (repos != null) {
            for (RepoConfig repo : repos) {
                if (repo.accept() != null) {
                    byRepo.put(repo.id(), new FileAcceptanceFilter(repo.accept()));
                }
            }
        }
    }

    public FileAcceptanceFilter forRepo(String repoId) {
        if (repoId == null) return defaultFilter;
        return byRepo.getOrDefault(repoId, defaultFilter);
    }

    public FileAcceptanceFilter defaultFilter() {
        return defaultFilter;
    }
}
