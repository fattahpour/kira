# Index Monitor Implementation Report

Date: 2026-06-18

## Summary

Implemented rich index monitoring for Kira:

- Per-repo and per-branch document counts, split by `CODE` and `KNOWLEDGE`
- Live active-indexing tracking through `IndexActivityTracker`
- Last indexed SHA from `CheckpointStore`
- Last sync timestamp from `BranchSyncScheduler`
- Rich status returned from MCP `index_status()`
- New REST endpoint: `GET /api/v1/index/monitor`

Expected JSON shape is now produced by both the REST endpoint and MCP tool:

```json
{
  "totalDocs": 1234,
  "serverVersion": "0.1.0",
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
  ],
  "anyIndexing": false
}
```

## Task Order

Implemented in the prompt's Spring-safe order:

1. Task 8: Register `IndexActivityTracker` bean
2. Task 1: Add `IndexActivityTracker`
3. Task 2: Add `NrtLuceneSearcher.count(SearchFilter)`
4. Task 3: Add `RepoIndexStats`
5. Task 4: Extend `IndexStatus`
6. Task 5: Expose `BranchSyncScheduler.getLastSync(repoId)`
7. Task 6: Wire tracker into `FullReindexService`
8. Task 7: Wire tracker into `BranchSyncService`
9. Task 9: Add `IndexMonitorService`
10. Task 10: Register `IndexMonitorService` bean
11. Task 11: Update MCP `index_status()`
12. Task 12: Add REST monitor endpoint

## Commits

- `2db2291` feat: register IndexActivityTracker as Spring bean
- `ffd56e6` feat: add IndexActivityTracker for per-repo indexing activity
- `73fd319` feat: add count(SearchFilter) to NrtLuceneSearcher
- `88f64fe` feat: add RepoIndexStats DTO
- `c9e3405` feat: extend IndexStatus with repos and anyIndexing fields
- `6a24ee9` feat: expose getLastSync(repoId) on BranchSyncScheduler
- `a09400e` feat: wire IndexActivityTracker into FullReindexService
- `dc1ed63` feat: wire IndexActivityTracker into BranchSyncService
- `d2e1aea` feat: add IndexMonitorService for per-repo/branch/domain index counts
- `e2665c5` feat: register IndexMonitorService bean
- `4301dfb` feat: index_status() now returns per-repo/branch/domain breakdown via IndexMonitorService
- `990f24c` feat: add GET /api/v1/index/monitor endpoint returning rich index status

## Verification

Focused checks run during implementation:

- `mvn test -pl . -Dtest=IndexActivityTrackerTest -q`
- `mvn test -pl . -Dtest=NrtLuceneSearcherTest -q`
- `mvn test -pl . -Dtest=IndexMonitorServiceTest -q`
- `mvn test -pl . -Dtest=IndexControllerMonitorTest -q`

Final verification:

- `mvn test -q` passed

Note: the prompt requested `./mvnw`, but this checkout does not contain a Maven wrapper script, so verification used the installed `mvn` command with the same Maven goals and selectors.

## Implementation Notes

- `IndexActivityTracker` and `IndexMonitorService` are framework-free classes; Spring beans are registered only in `config/`.
- `NrtLuceneSearcher.count(SearchFilter)` uses `IndexSearcher.count(Query)` with a `MatchAllDocsQuery` base and existing filter construction.
- `BranchSyncService` and `FullReindexService` both track the initial full reindex path for the same repo/branch key. This is safe because `ConcurrentHashMap.put` and `remove` make duplicate start/stop calls harmless.
- `IndexMonitorService` only reports configured repos and tracked branches, defaulting to `main` when no tracked branch list is configured.
