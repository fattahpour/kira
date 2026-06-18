# Branch Modes & Auto-Sync Implementation Report

Source plan: `docs/superpowers/plans/2026-06-16-branch-modes-autosync.md`

Date: 2026-06-17

## Scope

Implemented branch-aware repository indexing with `SINGLE` and `MULTI` branch tracking, checkpointed auto-sync, branch-filtered search, and configurable file acceptance. The companion file-acceptance plan had not been applied yet, so its required `AcceptConfig`, `FileAcceptanceFilter`, and `FilterRegistry` pieces were included as prerequisites.

## Implemented

- Expanded `ApplicationProps` with global accept filters, repo configs, branch configs, branch modes, and auto-sync configs.
- Added default accept include/exclude patterns and example repo configuration to `application.yml`.
- Added `branch` to `Chunk` and updated all chunk construction sites.
- Stored and indexed `branch` in Lucene documents.
- Added `LuceneIndexer.deleteByPathAndBranch` for branch-scoped cleanup.
- Added `branch` to `SearchFilter` and wired `NrtLuceneSearcher` filtering.
- Added `CheckpointStore` for per-repo/per-branch checkpoint persistence.
- Added `BranchResolver` for single-branch selection and multi-branch glob expansion against local Git refs.
- Updated `IndexService` for branch-aware incremental and single-file indexing while preserving branch-unaware overloads.
- Updated `FullReindexService` for branch-aware full reindexing while preserving branch-unaware overloads.
- Added `BranchSyncService` to choose full reindex, no-op, or incremental indexing based on checkpoints and branch HEADs.
- Added `BranchSyncScheduler` with scheduled auto-sync, manual sync trigger, and status reporting.
- Added a `TaskScheduler` bean and enabled scheduling.
- Wired `FilterRegistry`, `CheckpointStore`, `BranchResolver`, and branch sync services in `IngestConfig`.
- Updated index API requests and controller endpoints:
  - `POST /api/v1/index`
  - `POST /api/v1/index/incremental`
  - `POST /api/v1/index/full`
  - `POST /api/v1/index/sync/{repoId}`
  - `GET /api/v1/index/status`
- Updated search API requests to accept optional `branch`.
- Updated MCP search tools to accept optional `branch` filters.

## Tests Added Or Updated

- `FileAcceptanceFilterTest`
- `FilterRegistryTest`
- `CheckpointStoreTest`
- `BranchResolverTest`
- `SearchControllerIntegrationTest`
- `McpToolsTest`
- Existing Lucene/search tests updated for the new `Chunk.branch` constructor argument.

## Verification

Run from the project root:

```bash
mvn compile -q
mvn test -q
```

Completed verification:

- `mvn compile -q` passed.
- `mvn test -q` passed.
- `GET /api/v1/index/status` is covered by integration test and returns empty `checkpoints` and `lastSync` maps on a fresh isolated checkpoint file.

## Notes

- The workspace root was not a Git repository, so changes were verified with Maven and source sweeps rather than `git diff`.
- Parser APIs remain branch-unaware. Parsed chunks are stamped with the active branch in `IndexService` immediately before Lucene upsert.
- Backward-compatible branch-unaware index and search call paths are retained by passing `null` for branch.
