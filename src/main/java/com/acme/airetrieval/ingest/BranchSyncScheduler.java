package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.config.ApplicationProps.RepoConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
public class BranchSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(BranchSyncScheduler.class);

    private final ApplicationProps props;
    private final BranchSyncService syncService;
    private final CheckpointStore checkpointStore;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSync = new ConcurrentHashMap<>();

    public BranchSyncScheduler(ApplicationProps props, BranchSyncService syncService,
                               CheckpointStore checkpointStore, TaskScheduler taskScheduler) {
        this.props = props;
        this.syncService = syncService;
        this.checkpointStore = checkpointStore;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void scheduleAll() {
        List<RepoConfig> repos = props.repos();
        if (repos == null || repos.isEmpty()) return;
        for (RepoConfig repo : repos) {
            if (repo.autoSync() != null && repo.autoSync().enabled()) {
                int secs = repo.autoSync().intervalSeconds();
                if (secs <= 0) {
                    log.warn("Repo '{}' has invalid auto-sync interval {}s — skipping", repo.id(), secs);
                    continue;
                }
                Duration interval = Duration.ofSeconds(secs);
                ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                    () -> runSync(repo), Instant.now().plus(interval), interval);
                futures.put(repo.id(), future);
                log.info("Scheduled auto-sync for repo '{}' every {}s", repo.id(), secs);
            }
        }
    }

    public Map<String, IndexService.IndexResult> triggerSync(String repoId) throws Exception {
        List<RepoConfig> repos = props.repos();
        if (repos == null) {
            throw new IllegalArgumentException("Unknown repo: " + repoId);
        }
        RepoConfig repo = repos.stream()
            .filter(r -> r.id().equals(repoId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown repo: " + repoId));
        Map<String, IndexService.IndexResult> result = syncService.sync(repo);
        lastSync.put(repoId, Instant.now());
        return result;
    }

    public Map<String, Object> status() {
        return Map.of(
            "checkpoints", checkpointStore.snapshot(),
            "lastSync", Map.copyOf(lastSync)
        );
    }

    public java.time.Instant getLastSync(String repoId) {
        return lastSync.get(repoId);
    }

    private void runSync(RepoConfig repo) {
        Instant start = Instant.now();
        try {
            syncService.sync(repo);
            lastSync.put(repo.id(), Instant.now());
            log.info("Auto-sync '{}' done in {}ms",
                repo.id(), Duration.between(start, Instant.now()).toMillis());
        } catch (Exception e) {
            log.error("Auto-sync failed for repo '{}'", repo.id(), e);
        }
    }
}
