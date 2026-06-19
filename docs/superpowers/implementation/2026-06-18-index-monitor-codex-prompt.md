# Codex Prompt: Index Monitor Implementation

## Instructions for Codex

You are implementing index monitoring features for the Kira project — a Spring Boot 3.5 / Java 21 local AI code+knowledge retrieval platform.

Work in: `<kira-install-dir>`

Follow the plan at: `docs/superpowers/plans/2026-06-18-index-monitor.md`

**Implement every task in order (Task 1 through Task 12). For each task:**
1. Write the failing test(s) exactly as shown in the plan
2. Run the test to confirm it fails
3. Implement the code change exactly as shown
4. Run the test to confirm it passes
5. Commit exactly as shown in the plan
6. Move to the next task

**Do not skip tasks or combine them.**

After all 12 tasks, run:
```bash
./mvnw test -q
```
Expected: `BUILD SUCCESS` with no failures or errors.

Then write the implementation report to:
`docs/superpowers/implementation/2026-06-18-index-monitor-implementation.md`

---

## Context: What this builds

Rich index monitoring: how many documents are indexed, broken down by repo/branch/domain, plus a live active-indexing flag and last-sync metadata.

| Task | What | Files |
|------|------|-------|
| 1 | `IndexActivityTracker` — thread-safe per-repo:branch active flag | `ingest/IndexActivityTracker.java` |
| 2 | `count(SearchFilter)` on `NrtLuceneSearcher` — filtered doc count | `index/NrtLuceneSearcher.java` |
| 3 | `RepoIndexStats` record — per-repo/branch stats DTO | `retrieve/dto/RepoIndexStats.java` |
| 4 | Extend `IndexStatus` — add `repos` list + `anyIndexing` flag | `retrieve/dto/IndexStatus.java`, `mcp/McpTools.java` |
| 5 | `BranchSyncScheduler.getLastSync()` — expose last sync timestamp | `ingest/BranchSyncScheduler.java` |
| 6 | Wire tracker into `FullReindexService` — mark start/stop per job | `ingest/FullReindexService.java`, `config/IngestConfig.java` |
| 7 | Wire tracker into `BranchSyncService` — mark start/stop per branch | `ingest/BranchSyncService.java`, `config/IngestConfig.java` |
| 8 | Register `IndexActivityTracker` bean | `config/IngestConfig.java` |
| 9 | `IndexMonitorService` — assemble full status from Lucene + checkpoint + scheduler + tracker | `index/IndexMonitorService.java` |
| 10 | Register `IndexMonitorService` bean | `config/ObserveConfig.java` |
| 11 | Update `McpTools.index_status()` — return rich status | `mcp/McpTools.java` |
| 12 | `GET /api/v1/index/monitor` REST endpoint | `api/IndexController.java` |

---

## Key architectural rules (do not violate)

- Layering: `ingest → embed → index/graph → retrieve → (mcp | api)`. Engine packages (`embed`, `index`, `graph`, `retrieve`) must stay framework-free — no Spring annotations.
- No Python. No external DB. All offline.
- `IndexMonitorService` lives in `index/` package (framework-free). No `@Component`.
- `IndexActivityTracker` lives in `ingest/` package (framework-free). No `@Component`.
- Beans registered only in `config/` classes.
- Tasks must be done in order. **Task 8 must be done before Tasks 6, 7, and 10** — those tasks inject the `IndexActivityTracker` bean and the Spring context will fail if the bean is unregistered.

## Recommended task order

Do them in this order to avoid Spring context failures during compilation checks:

```
8 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 9 → 10 → 11 → 12
```

(Task 8 registers the bean. Tasks 6 and 7 inject it. Do 8 first, then 1–7 are safe in any order.)

---

## Critical implementation notes

### Task 2 — `count(SearchFilter)`
- Use `IndexSearcher.count(Query)` — no scoring, no `ScoreDoc[]` allocation. Fastest path.
- `MatchAllDocsQuery` as base when filter is null or all-null fields.
- The `buildFilter(SearchFilter)` method is already `private` in `NrtLuceneSearcher` — call it directly since `count()` is added to the same class.

### Task 4 — Extending `IndexStatus`
- `IndexStatus` is a record. Records don't support default values. Add a `static IndexStatus.simple(int, String)` factory for the `refresh_index()` call sites.
- Two existing `new IndexStatus(...)` calls in `McpTools.refresh_index()` must be updated to use the 4-arg constructor with `null` for `repos` and `false` for `anyIndexing`.

### Task 6 — `FullReindexService` activity tracking
- Wrap the entire body of `reindex(String repo, String branch, Path repoDir, String gitSha)` in `try { ... } finally { activityTracker.stop(repo, branch); }`.
- Call `activityTracker.start(repo, branch)` before the `try` block, not inside it.
- The 3-arg overload `reindex(String repo, Path repoDir, String gitSha)` delegates to the 4-arg version so it is automatically covered — do not double-wrap it.

### Task 7 — `BranchSyncService` activity tracking
- When `fromSha == null`, `BranchSyncService.sync()` calls `fullReindexService.reindex()` which also calls `activityTracker.start()` for the same key. This is safe — `ConcurrentHashMap.put` is idempotent and both `stop()` calls fire harmlessly (second is a no-op since the key is already absent).

### Task 9 — `IndexMonitorService`
- Read configured repos from `ApplicationProps.repos()`. If null or empty, return empty list — do not scan the Lucene index for unknown repos.
- For each repo, iterate `trackedBranches(repo)`: use `repo.branches().tracked()` if non-empty, else `List.of("main")`.
- `syncScheduler` may be `null` in test contexts — guard with `syncScheduler != null ? syncScheduler.getLastSync(repo.id()) : null`.
- `lastSyncAt` stored as ISO-8601 string in `RepoIndexStats` so it serializes cleanly to JSON without custom converters.

### Task 11 — `McpTools.index_status()` return type
- The `@Tool` annotation method return type changes from the old 2-field `IndexStatus` to the 4-field version. Spring AI serializes the record to JSON — the new `repos` and `anyIndexing` fields appear automatically in the MCP response.

### Task 12 — REST endpoint test
- Use `@WebMvcTest(IndexController.class)` with `@MockBean` for all injected services. Do not use `@SpringBootTest` — it requires the full context including Lucene on disk and ONNX models.

---

## File locations (exact package paths)

```
src/main/java/com/acme/airetrieval/
├── api/
│   └── IndexController.java                         ← modify (Task 12)
├── config/
│   ├── IngestConfig.java                            ← modify (Tasks 6, 7, 8)
│   └── ObserveConfig.java                           ← modify (Task 10)
├── index/
│   ├── IndexMonitorService.java                     ← create (Task 9)
│   └── NrtLuceneSearcher.java                       ← modify (Task 2)
├── ingest/
│   ├── BranchSyncScheduler.java                     ← modify (Task 5)
│   ├── BranchSyncService.java                       ← modify (Task 7)
│   ├── FullReindexService.java                      ← modify (Task 6)
│   └── IndexActivityTracker.java                    ← create (Task 1)
├── mcp/
│   └── McpTools.java                                ← modify (Tasks 4, 11)
└── retrieve/dto/
    ├── IndexStatus.java                             ← modify (Task 4)
    └── RepoIndexStats.java                          ← create (Task 3)

src/test/java/com/acme/airetrieval/
├── api/
│   └── IndexControllerMonitorTest.java              ← create (Task 12)
├── index/
│   └── IndexMonitorServiceTest.java                 ← create (Task 9)
└── ingest/
    └── IndexActivityTrackerTest.java                ← create (Task 1)
```

---

## Verification after all tasks

```bash
./mvnw test -q
```

Expected response shape from `GET /api/v1/index/monitor`:

```json
{
  "totalDocs": 1234,
  "serverVersion": "0.1.0",
  "anyIndexing": false,
  "repos": [
    {
      "repo": "myrepo",
      "branch": "main",
      "codeDocs": 900,
      "knowledgeDocs": 334,
      "totalDocs": 1234,
      "lastSha": "abc1234",
      "lastSyncAt": "2026-06-18T10:30:00Z",
      "indexing": false
    }
  ]
}
```

Expected response shape from `index_status()` MCP tool — same JSON structure.

When a full reindex is running, `anyIndexing` is `true` and the affected repo entry has `"indexing": true`.
