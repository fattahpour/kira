# Kira — Branch Auto-Sync Bug Fixes

Fixes found during code review of the branch-modes and file-acceptance implementation.

## Codex Invocation

```bash
codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
  -C <kira-install-dir> \
  "Read and apply the fix plan at docs/superpowers/plans/2026-06-17-branch-autosync-fixes.md. For each step: verify whether the fix is already applied, apply it if not. Run: mvn compile -q && mvn test -q at the end and confirm both pass."
```

---

## Bugs Found

| # | Severity | File | Issue |
|---|----------|------|-------|
| 1 | **Bug** | `CheckpointStore` | `HashMap` accessed from multiple scheduler threads — data corruption under concurrent writes |
| 2 | **Bug** | `BranchSyncScheduler` | `lastSync` and `futures` are plain `HashMap`s — written from scheduler threads, read from HTTP thread |
| 3 | **Bug** | `IndexService` | Content-hash dedup in `indexIncremental` is branch-unaware — branch A's hash silently suppresses branch B's re-index |
| 4 | **Suggestion** | `BranchSyncScheduler` | `intervalSeconds <= 0` throws cryptic `IllegalArgumentException` inside `scheduleAtFixedRate` instead of a clear startup error |

---

## Fix 1 — `CheckpointStore`: synchronized methods

`state` is a plain `HashMap`. Two scheduler threads syncing different repos call `put` concurrently → undefined behaviour. `flush()` must also serialize a consistent snapshot.

- [ ] Verify `CheckpointStore.java` has `synchronized` on `get`, `put`, `flush`, `snapshot`.
- [ ] Verify `flush()` copies `state` to a local `HashMap` under the lock before writing JSON (so the lock is held only for the copy, not the I/O).

**Target state of `CheckpointStore.java`:**

```java
package com.acme.airetrieval.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class CheckpointStore {
    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, String> state;

    public CheckpointStore(Path file, ObjectMapper mapper) throws IOException {
        this.file = file;
        this.mapper = mapper;
        if (Files.exists(file)) {
            state = mapper.readValue(file.toFile(), new TypeReference<HashMap<String, String>>() {});
        } else {
            state = new HashMap<>();
        }
    }

    public synchronized Optional<String> get(String repoId, String branch) {
        return Optional.ofNullable(state.get(key(repoId, branch)));
    }

    public synchronized void put(String repoId, String branch, String sha) {
        state.put(key(repoId, branch), sha);
    }

    public synchronized void flush() throws IOException {
        Map<String, String> copy = new HashMap<>(state);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), copy);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(state);
    }

    private String key(String repoId, String branch) {
        return (branch == null || branch.isBlank()) ? repoId : repoId + ":" + branch;
    }
}
```

---

## Fix 2 — `BranchSyncScheduler`: `ConcurrentHashMap` + safe `status()`

`lastSync` is written from `kira-sync-*` scheduler threads and read from the HTTP thread in `status()`. `futures` is written in `@PostConstruct` and will be read if we ever add a cancel operation.

- [ ] Verify `futures` and `lastSync` are declared as `ConcurrentHashMap`.
- [ ] Verify `status()` returns `Map.copyOf(lastSync)` — not the live map directly.
- [ ] Verify import: `java.util.concurrent.ConcurrentHashMap` is present; `java.util.HashMap` is removed from imports.

**Target state of `BranchSyncScheduler.java`:**

```java
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
```

---

## Fix 3 — `IndexService`: skip branch-unaware hash dedup when `branch != null`

`getContentHash(path + "#body")` queries Lucene by chunk ID without a branch filter. If branch A and branch B both have a file at the same path, the hash from branch A could prevent re-indexing branch B's different version of that file.

Git diff already guarantees the file changed when it appears in the change list — the hash check is only a guard against edge cases in branch-unaware (single-branch) mode.

- [ ] Verify `indexIncremental` wraps the hash-check block in `if (branch == null) { ... }`.

**Target state of the relevant block inside `indexIncremental`:**

```java
            Path file = repoPath.resolve(change.path());
            if (!Files.isRegularFile(file)) continue;
            String text = Files.readString(file);
            // Hash dedup only when branch-unaware: cross-branch hash leakage would cause false skips.
            if (branch == null) {
                String existingHash = searcher.getContentHash(change.path() + "#body").orElse(null);
                String newHash = MarkdownParser.hash(text);
                if (newHash.equals(existingHash)) {
                    skipped++;
                    continue;
                }
            }
            indexer.deleteByPathAndBranch(change.path(), branch);
            indexed += indexAndEmbed(repo, branch, change.path(), toSha, text);
```

---

## Fix 4 — `BranchSyncScheduler.scheduleAll`: guard `intervalSeconds <= 0`

`Duration.ofSeconds(0)` causes `scheduleAtFixedRate` to throw `IllegalArgumentException` at startup with no indication of which repo misconfigured. The guard logs a clear warning and skips the bad entry instead.

- [ ] Verify `scheduleAll()` checks `secs <= 0` and calls `log.warn(...)` + `continue` before creating the `Duration`.

This fix is included in the `BranchSyncScheduler` target state shown in Fix 2 above.

---

## Verification

- [ ] `mvn compile -q` — zero errors.
- [ ] `mvn test -q` — all tests pass (expect 43 total).
