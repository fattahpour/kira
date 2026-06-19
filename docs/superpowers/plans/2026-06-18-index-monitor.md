# Index Monitoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add rich index monitoring — per-repo/branch/domain document counts, live activity flag, and last-sync metadata — exposed via the `index_status()` MCP tool and a new REST endpoint.

**Architecture:** A new `IndexActivityTracker` records which repo:branch is currently being indexed. A new `IndexMonitorService` assembles counts from Lucene (filtered by repo/branch/domain), last-SHA from `CheckpointStore`, last-sync timestamps from `BranchSyncScheduler`, and active flags from the tracker. `McpTools.index_status()` and a new `GET /api/v1/index/monitor` endpoint both delegate to this service.

**Tech Stack:** Apache Lucene 10 `IndexSearcher.count(Query)`, Spring Boot beans, existing `NrtLuceneSearcher`, `CheckpointStore`, `BranchSyncScheduler`, `ApplicationProps`.

---

## File Map

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `ingest/IndexActivityTracker.java` | Thread-safe per-repo:branch active-indexing flag |
| Create | `retrieve/dto/RepoIndexStats.java` | Per-repo/branch stats record |
| Create | `index/IndexMonitorService.java` | Assemble full index status |
| Create | `src/test/…/ingest/IndexActivityTrackerTest.java` | Unit tests for tracker |
| Create | `src/test/…/index/IndexMonitorServiceTest.java` | Integration test with real Lucene |
| Modify | `retrieve/dto/IndexStatus.java` | Add `repos` + `anyIndexing` fields |
| Modify | `index/NrtLuceneSearcher.java` | Add `count(SearchFilter)` method |
| Modify | `ingest/FullReindexService.java` | Inject tracker, mark start/stop |
| Modify | `ingest/BranchSyncService.java` | Inject tracker, mark start/stop for incremental |
| Modify | `ingest/BranchSyncScheduler.java` | Expose `getLastSync(repoId)` |
| Modify | `config/IngestConfig.java` | Register `IndexActivityTracker` bean |
| Modify | `config/ObserveConfig.java` | Register `IndexMonitorService` bean |
| Modify | `mcp/McpTools.java` | Inject `IndexMonitorService`, update `index_status()` |
| Modify | `api/IndexController.java` | Add `GET /api/v1/index/monitor` endpoint |

---

## Task 1: `IndexActivityTracker`

**Files:**
- Create: `src/main/java/com/acme/airetrieval/ingest/IndexActivityTracker.java`
- Create: `src/test/java/com/acme/airetrieval/ingest/IndexActivityTrackerTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/peyman/Desktop/workstation/kira
./mvnw test -pl . -Dtest=IndexActivityTrackerTest -q 2>&1 | tail -5
```

Expected: compilation error — `IndexActivityTracker` does not exist.

- [ ] **Step 3: Implement `IndexActivityTracker`**

```java
package com.acme.airetrieval.ingest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IndexActivityTracker {
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();

    public void start(String repo, String branch) {
        active.put(key(repo, branch), Boolean.TRUE);
    }

    public void stop(String repo, String branch) {
        active.remove(key(repo, branch));
    }

    public boolean isActive(String repo, String branch) {
        return active.containsKey(key(repo, branch));
    }

    public boolean anyActive() {
        return !active.isEmpty();
    }

    public Set<String> activeKeys() {
        return Set.copyOf(active.keySet());
    }

    private static String key(String repo, String branch) {
        return branch != null && !branch.isBlank() ? repo + ":" + branch : repo + ":";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -pl . -Dtest=IndexActivityTrackerTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/IndexActivityTracker.java \
        src/test/java/com/acme/airetrieval/ingest/IndexActivityTrackerTest.java
git commit -m "feat: add IndexActivityTracker for per-repo indexing activity"
```

---

## Task 2: `count(SearchFilter)` on `NrtLuceneSearcher`

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`
- Modify: `src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java`

- [ ] **Step 1: Write the failing test**

Add to `NrtLuceneSearcherTest`:

```java
@Test
void countByFilter() throws Exception {
    // index one CODE doc in repo1/main and one KNOWLEDGE doc in repo1/main
    LuceneIndexer indexer = new LuceneIndexer(tempDir.resolve("cnt"));
    NrtLuceneSearcher searcher = new NrtLuceneSearcher(indexer.getWriter());

    indexer.upsert(new Chunk("c1", "repo1", "main", "Foo.java", Domain.CODE, "CLASS",
        "Foo", null, null, List.of(), "sha1", "h1", "java", "class Foo {}", null));
    indexer.upsert(new Chunk("k1", "repo1", "main", "README.md", Domain.KNOWLEDGE, "MARKDOWN",
        null, "README", null, List.of(), "sha1", "h2", "md", "# Readme", null));
    indexer.commit();
    searcher.maybeReopen();

    assertThat(searcher.count(new SearchFilter("repo1", "CODE", null, null, "main"))).isEqualTo(1);
    assertThat(searcher.count(new SearchFilter("repo1", "KNOWLEDGE", null, null, "main"))).isEqualTo(1);
    assertThat(searcher.count(new SearchFilter("repo1", null, null, null, "main"))).isEqualTo(2);
    assertThat(searcher.count(new SearchFilter("repo2", null, null, null, null))).isEqualTo(0);

    searcher.close();
    indexer.close();
}
```

(Import `com.acme.airetrieval.ingest.model.Domain` and `Chunk`.)

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -pl . -Dtest=NrtLuceneSearcherTest#countByFilter -q 2>&1 | tail -5
```

Expected: compilation error — `count` method does not exist.

- [ ] **Step 3: Add `count(SearchFilter)` to `NrtLuceneSearcher`**

Add this method after the `knn` method (around line 55):

```java
public int count(SearchFilter filter) throws IOException {
    IndexSearcher s = manager.acquire();
    try {
        Query base = new MatchAllDocsQuery();
        Query filterQuery = buildFilter(filter);
        Query q = filterQuery == null ? base :
            new BooleanQuery.Builder()
                .add(base, BooleanClause.Occur.MUST)
                .add(filterQuery, BooleanClause.Occur.FILTER)
                .build();
        return s.count(q);
    } finally {
        manager.release(s);
    }
}
```

Add import at the top of `NrtLuceneSearcher.java`:

```java
import org.apache.lucene.search.MatchAllDocsQuery;
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -pl . -Dtest=NrtLuceneSearcherTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java \
        src/test/java/com/acme/airetrieval/index/NrtLuceneSearcherTest.java
git commit -m "feat: add count(SearchFilter) to NrtLuceneSearcher"
```

---

## Task 3: `RepoIndexStats` record

**Files:**
- Create: `src/main/java/com/acme/airetrieval/retrieve/dto/RepoIndexStats.java`

- [ ] **Step 1: Create the record**

```java
package com.acme.airetrieval.retrieve.dto;

public record RepoIndexStats(
    String repo,
    String branch,
    int codeDocs,
    int knowledgeDocs,
    int totalDocs,
    String lastSha,
    String lastSyncAt,
    boolean indexing
) {}
```

`lastSyncAt` is an ISO-8601 string (e.g. `"2026-06-18T10:30:00Z"`) or `null` if never synced.
`lastSha` is the last committed git SHA for this repo:branch, or `null` if never indexed.
`indexing` is `true` when a reindex or incremental sync is currently running.

- [ ] **Step 2: Verify it compiles**

```bash
./mvnw compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/dto/RepoIndexStats.java
git commit -m "feat: add RepoIndexStats DTO"
```

---

## Task 4: Extend `IndexStatus` record

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/retrieve/dto/IndexStatus.java`

Current: `record IndexStatus(int totalDocs, String serverVersion)`

The two existing call sites in `McpTools.java`:
1. `refresh_index()` → `new IndexStatus(result.indexed(), serverVersion + "...")`
2. `index_status()` → `new IndexStatus(retrieval.indexDocCount(), serverVersion)`

- [ ] **Step 1: Replace `IndexStatus` with extended version**

```java
package com.acme.airetrieval.retrieve.dto;

import java.util.List;

public record IndexStatus(
    int totalDocs,
    String serverVersion,
    List<RepoIndexStats> repos,
    boolean anyIndexing
) {
    public static IndexStatus simple(int totalDocs, String serverVersion) {
        return new IndexStatus(totalDocs, serverVersion, null, false);
    }
}
```

- [ ] **Step 2: Fix call sites in `McpTools.java`**

Find `new IndexStatus(result.indexed(), serverVersion + " [reindex complete:` and replace:

```java
// refresh_index() — replace both IndexStatus constructor calls:
return new IndexStatus(result.indexed(), serverVersion + " [reindex complete: " + repo + "]", null, false);
// ...
return new IndexStatus(-1, serverVersion + " [ERROR: " + e.getMessage() + "]", null, false);
```

- [ ] **Step 3: Verify it compiles**

```bash
./mvnw compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/retrieve/dto/IndexStatus.java \
        src/main/java/com/acme/airetrieval/mcp/McpTools.java
git commit -m "feat: extend IndexStatus with repos and anyIndexing fields"
```

---

## Task 5: `BranchSyncScheduler.getLastSync()`

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/BranchSyncScheduler.java`

- [ ] **Step 1: Add `getLastSync` method**

In `BranchSyncScheduler.java`, add this method after the existing `status()` method:

```java
public java.time.Instant getLastSync(String repoId) {
    return lastSync.get(repoId);
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./mvnw compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/BranchSyncScheduler.java
git commit -m "feat: expose getLastSync(repoId) on BranchSyncScheduler"
```

---

## Task 6: Wire `IndexActivityTracker` into `FullReindexService`

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/FullReindexService.java`

- [ ] **Step 1: Add tracker field and inject it**

Add the field:

```java
private final IndexActivityTracker activityTracker;
```

Change the constructor signature (add `IndexActivityTracker activityTracker` as last param):

```java
public FullReindexService(IndexService indexService, NrtLuceneSearcher searcher,
                          ExecutorService executor, ApplicationProps props,
                          FilterRegistry filterRegistry,
                          IndexActivityTracker activityTracker) {
    this.indexService = indexService;
    this.searcher = searcher;
    this.executor = executor;
    this.filterRegistry = filterRegistry;
    this.batchSize = Math.max(1, props.fullReindex().batchSize());
    this.respectGitignore = props.respectGitignore();
    this.activityTracker = activityTracker;
}
```

- [ ] **Step 2: Wrap `reindex(String repo, String branch, Path repoDir, String gitSha)` with tracker**

At the start of that method, add:

```java
activityTracker.start(repo, branch);
```

At the end (before `return`), replace the final `return` with:

```java
try {
    // ... existing body unchanged ...
} finally {
    activityTracker.stop(repo, branch);
}
```

Concrete: wrap the entire method body in `try { ... } finally { activityTracker.stop(repo, branch); }`.

The method after change looks like:

```java
public FullReindexResult reindex(String repo, String branch, Path repoDir, String gitSha) throws Exception {
    activityTracker.start(repo, branch);
    try {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        GitIgnoreFilter gitFilter = respectGitignore
            ? GitIgnoreFilter.forRepo(repoDir)
            : GitIgnoreFilter.disabled();
        List<Path> files;
        try (var stream = Files.walk(repoDir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(file -> {
                    String rel = repoDir.relativize(file).toString().replace('\\', '/');
                    return !gitFilter.isIgnored(rel)
                        && filter.accept(rel)
                        && classifier.classify(rel) != SourceClassifier.SourceType.IGNORE;
                })
                .toList();
        }
        AtomicInteger indexed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        List<Future<IndexService.IndexResult>> batch = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String rel = repoDir.relativize(file).toString().replace('\\', '/');
            batch.add(executor.submit(() -> indexService.indexFile(repo, branch, repoDir, rel, gitSha)));
            boolean lastFile = i == files.size() - 1;
            if (batch.size() >= batchSize || lastFile) {
                for (Future<IndexService.IndexResult> future : batch) {
                    IndexService.IndexResult result = future.get();
                    indexed.addAndGet(result.indexed());
                    skipped.addAndGet(result.skipped());
                }
                batch.clear();
            }
        }
        searcher.maybeReopen();
        return new FullReindexResult(indexed.get(), skipped.get(), files.size());
    } finally {
        activityTracker.stop(repo, branch);
    }
}
```

(The null-branch overload `reindex(String repo, Path repoDir, String gitSha)` delegates to the above, so it's automatically covered.)

- [ ] **Step 3: Update `IngestConfig` to pass the tracker**

In `IngestConfig.java`, change `fullReindexService` bean (Task 8 will register the tracker bean — do this step after Task 8 is complete, or as part of the same commit):

```java
@Bean
public FullReindexService fullReindexService(IndexService indexService, NrtLuceneSearcher searcher,
                                              ExecutorService executor, ApplicationProps props,
                                              FilterRegistry filterRegistry,
                                              IndexActivityTracker activityTracker) {
    return new FullReindexService(indexService, searcher, executor, props, filterRegistry, activityTracker);
}
```

- [ ] **Step 4: Verify it compiles**

```bash
./mvnw compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS` (will fail until `IndexActivityTracker` bean is registered — do Task 8 first, then come back here if needed).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/FullReindexService.java \
        src/main/java/com/acme/airetrieval/config/IngestConfig.java
git commit -m "feat: wire IndexActivityTracker into FullReindexService"
```

---

## Task 7: Wire `IndexActivityTracker` into `BranchSyncService`

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/ingest/BranchSyncService.java`

This covers the incremental sync path (`indexService.indexIncremental()`).

- [ ] **Step 1: Add tracker field and inject it**

```java
private final IndexActivityTracker activityTracker;

public BranchSyncService(BranchResolver branchResolver, CheckpointStore checkpointStore,
                         IndexService indexService, FullReindexService fullReindexService,
                         IndexActivityTracker activityTracker) {
    this.branchResolver = branchResolver;
    this.checkpointStore = checkpointStore;
    this.indexService = indexService;
    this.fullReindexService = fullReindexService;
    this.activityTracker = activityTracker;
}
```

- [ ] **Step 2: Wrap `sync()` method with tracker**

The `sync()` method iterates branches. Wrap the per-branch work:

```java
public Map<String, IndexService.IndexResult> sync(RepoConfig repo) throws Exception {
    List<String> branches = branchResolver.resolve(repo.path(), repo.branches());
    Map<String, IndexService.IndexResult> results = new LinkedHashMap<>();
    for (String branch : branches) {
        activityTracker.start(repo.id(), branch);
        try {
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
        } finally {
            activityTracker.stop(repo.id(), branch);
        }
    }
    checkpointStore.flush();
    return results;
}
```

Note: When `fromSha == null`, both `BranchSyncService` and `FullReindexService` will call `activityTracker.start` for the same key. The `ConcurrentHashMap.put` is idempotent — both `stop` calls will fire and the second will be a no-op (key already removed). This is safe.

- [ ] **Step 3: Update `IngestConfig` to pass the tracker to `BranchSyncService`**

```java
@Bean
public BranchSyncService branchSyncService(BranchResolver branchResolver, CheckpointStore checkpointStore,
                                           IndexService indexService, FullReindexService fullReindexService,
                                           IndexActivityTracker activityTracker) {
    return new BranchSyncService(branchResolver, checkpointStore, indexService, fullReindexService, activityTracker);
}
```

- [ ] **Step 4: Verify it compiles**

```bash
./mvnw compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/ingest/BranchSyncService.java \
        src/main/java/com/acme/airetrieval/config/IngestConfig.java
git commit -m "feat: wire IndexActivityTracker into BranchSyncService"
```

---

## Task 8: Register `IndexActivityTracker` bean + `IndexMonitorService` bean skeleton

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/config/IngestConfig.java`
- Modify: `src/main/java/com/acme/airetrieval/config/ObserveConfig.java`

Do this task before Tasks 6 and 7 if Spring DI fails due to missing bean.

- [ ] **Step 1: Register `IndexActivityTracker` in `IngestConfig`**

Add to `IngestConfig.java`:

```java
@Bean
public IndexActivityTracker indexActivityTracker() {
    return new IndexActivityTracker();
}
```

Add import: `import com.acme.airetrieval.ingest.IndexActivityTracker;`

- [ ] **Step 2: Verify it compiles**

```bash
./mvnw compile -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/IngestConfig.java
git commit -m "feat: register IndexActivityTracker as Spring bean"
```

---

## Task 9: `IndexMonitorService`

**Files:**
- Create: `src/main/java/com/acme/airetrieval/index/IndexMonitorService.java`
- Create: `src/test/java/com/acme/airetrieval/index/IndexMonitorServiceTest.java`

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -pl . -Dtest=IndexMonitorServiceTest -q 2>&1 | tail -5
```

Expected: compilation error — `IndexMonitorService` does not exist.

- [ ] **Step 3: Implement `IndexMonitorService`**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./mvnw test -pl . -Dtest=IndexMonitorServiceTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/index/IndexMonitorService.java \
        src/test/java/com/acme/airetrieval/index/IndexMonitorServiceTest.java
git commit -m "feat: add IndexMonitorService for per-repo/branch/domain index counts"
```

---

## Task 10: Register `IndexMonitorService` bean

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/config/ObserveConfig.java`

- [ ] **Step 1: Add `IndexMonitorService` bean**

```java
package com.acme.airetrieval.config;

import com.acme.airetrieval.index.IndexMonitorService;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.BranchSyncScheduler;
import com.acme.airetrieval.ingest.CheckpointStore;
import com.acme.airetrieval.ingest.IndexActivityTracker;
import com.acme.airetrieval.observe.MetricsService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserveConfig {
    @Bean
    public MetricsService metricsService(MeterRegistry registry) {
        return new MetricsService(registry);
    }

    @Bean
    public IndexMonitorService indexMonitorService(NrtLuceneSearcher searcher,
                                                   CheckpointStore checkpointStore,
                                                   BranchSyncScheduler syncScheduler,
                                                   IndexActivityTracker activityTracker,
                                                   ApplicationProps props) {
        return new IndexMonitorService(searcher, checkpointStore, syncScheduler, activityTracker, props);
    }
}
```

- [ ] **Step 2: Verify it compiles and all existing tests pass**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/acme/airetrieval/config/ObserveConfig.java
git commit -m "feat: register IndexMonitorService bean"
```

---

## Task 11: Update `McpTools.index_status()` to return rich status

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/mcp/McpTools.java`

- [ ] **Step 1: Inject `IndexMonitorService` into `McpTools`**

Add field:

```java
private final IndexMonitorService indexMonitorService;
```

Change constructor:

```java
public McpTools(RetrievalOrchestrator retrieval, GraphQueries graph,
                FullReindexService reindexService,
                IndexMonitorService indexMonitorService,
                @Value("${spring.ai.mcp.server.version:0.1.0}") String serverVersion) {
    this.retrieval = retrieval;
    this.graph = graph;
    this.reindexService = reindexService;
    this.indexMonitorService = indexMonitorService;
    this.serverVersion = serverVersion;
}
```

Add import: `import com.acme.airetrieval.index.IndexMonitorService;`

- [ ] **Step 2: Replace `index_status()` body**

```java
@Tool(description = "Return number of indexed documents, server version, per-repo/branch/domain counts, and active indexing flag")
public IndexStatus index_status() {
    try {
        return indexMonitorService.buildStatus(serverVersion);
    } catch (Exception e) {
        return new IndexStatus(-1, serverVersion + " [ERROR: " + e.getMessage() + "]", null, false);
    }
}
```

- [ ] **Step 3: Verify all tests pass**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/acme/airetrieval/mcp/McpTools.java
git commit -m "feat: index_status() now returns per-repo/branch/domain breakdown via IndexMonitorService"
```

---

## Task 12: Add `GET /api/v1/index/monitor` REST endpoint

**Files:**
- Modify: `src/main/java/com/acme/airetrieval/api/IndexController.java`

- [ ] **Step 1: Inject `IndexMonitorService` and add endpoint**

Add field:

```java
private final IndexMonitorService indexMonitorService;
private final String serverVersion;
```

Change constructor:

```java
public IndexController(IndexService indexService, FullReindexService fullReindexService,
                       BranchSyncScheduler syncScheduler, IndexMonitorService indexMonitorService,
                       @Value("${spring.ai.mcp.server.version:0.1.0}") String serverVersion) {
    this.indexService = indexService;
    this.fullReindexService = fullReindexService;
    this.syncScheduler = syncScheduler;
    this.indexMonitorService = indexMonitorService;
    this.serverVersion = serverVersion;
}
```

Add import:
```java
import com.acme.airetrieval.index.IndexMonitorService;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import org.springframework.beans.factory.annotation.Value;
```

Add endpoint after existing `status()` method:

```java
@GetMapping("/monitor")
public IndexStatus monitor() throws Exception {
    return indexMonitorService.buildStatus(serverVersion);
}
```

- [ ] **Step 2: Write a quick integration test**

Add to `src/test/java/com/acme/airetrieval/api/SearchControllerIntegrationTest.java` or create a new `IndexControllerMonitorTest.java`:

```java
package com.acme.airetrieval.api;

import com.acme.airetrieval.index.IndexMonitorService;
import com.acme.airetrieval.retrieve.dto.IndexStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IndexController.class)
class IndexControllerMonitorTest {

    @Autowired MockMvc mockMvc;

    @MockBean IndexMonitorService indexMonitorService;
    @MockBean com.acme.airetrieval.ingest.IndexService indexService;
    @MockBean com.acme.airetrieval.ingest.FullReindexService fullReindexService;
    @MockBean com.acme.airetrieval.ingest.BranchSyncScheduler syncScheduler;

    @Test
    void monitorEndpointReturnsStatus() throws Exception {
        when(indexMonitorService.buildStatus(any())).thenReturn(
            new IndexStatus(42, "0.1.0", java.util.List.of(), false));

        mockMvc.perform(get("/api/v1/index/monitor"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalDocs").value(42))
            .andExpect(jsonPath("$.serverVersion").value("0.1.0"))
            .andExpect(jsonPath("$.anyIndexing").value(false));
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

```bash
./mvnw test -pl . -Dtest=IndexControllerMonitorTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Verify all tests pass**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/acme/airetrieval/api/IndexController.java \
        src/test/java/com/acme/airetrieval/api/IndexControllerMonitorTest.java
git commit -m "feat: add GET /api/v1/index/monitor endpoint returning rich index status"
```

---

## Self-Review

### Spec coverage

| Requirement | Task(s) |
|-------------|---------|
| How much index (total count) | Tasks 2, 9 — `numDocs()` + `buildStatus()` |
| Per-repo/branch/domain breakdown | Tasks 2, 3, 9 — `count(SearchFilter)` + `RepoIndexStats` + `IndexMonitorService` |
| Is index active or not | Tasks 1, 6, 7, 9 — `IndexActivityTracker` wired into FullReindex + BranchSync |
| Last indexed SHA per repo:branch | Task 9 — `CheckpointStore.get()` in `IndexMonitorService` |
| Last sync time per repo | Tasks 5, 9 — `getLastSync()` + used in `IndexMonitorService` |
| MCP tool exposes rich status | Task 11 |
| REST endpoint for monitoring | Task 12 |
| Tests for all new code | Tasks 1, 2, 9, 12 |

### Placeholder scan
None found. All steps contain concrete code.

### Type consistency
- `IndexStatus(int totalDocs, String serverVersion, List<RepoIndexStats> repos, boolean anyIndexing)` — defined Task 4, used in Tasks 9, 11, 12. ✓
- `RepoIndexStats(String repo, String branch, int codeDocs, int knowledgeDocs, int totalDocs, String lastSha, String lastSyncAt, boolean indexing)` — defined Task 3, populated Task 9, asserted Task 9 test. ✓
- `IndexActivityTracker.start(String repo, String branch)` — defined Task 1, used Tasks 6, 7, test Task 1. ✓
- `IndexMonitorService.buildStatus(String serverVersion)` — defined Task 9, wired Task 10, called Tasks 11, 12. ✓
- `BranchSyncScheduler.getLastSync(String repoId)` — defined Task 5, called Task 9. ✓
- `NrtLuceneSearcher.count(SearchFilter)` — defined Task 2, called Task 9. ✓

---

**Plan complete and saved to `docs/superpowers/plans/2026-06-18-index-monitor.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — Fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
