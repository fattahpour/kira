# Documentation Update Report

Date: 2026-06-17

## Scope

Updated user-facing documentation and API examples after branch auto-sync, branch filtering, and file acceptance support were implemented.

## Updated

- `README.md`
  - Added branch-aware indexing, auto-sync, checkpoints, and file acceptance to the feature and configuration sections.
  - Corrected single-file indexing to use `POST /api/v1/index`.
  - Clarified Git incremental indexing with `fromSha`, `toSha`, and optional `branch`.
  - Added branch auto-sync configuration examples and status/sync commands.
  - Added branch-filtered search examples and troubleshooting notes.

- `docs/TUTORIAL.md`
  - Corrected manual indexing examples.
  - Added branch-aware indexing, Git incremental indexing, auto-sync configuration, sync/status calls, and branch search.
  - Updated reset and Swagger/HTTP client workflows.

- `docs/SETUP_AND_PERFORMANCE.md`
  - Added branch auto-sync and file acceptance configuration.
  - Updated indexing and search examples for branch-aware workflows.
  - Added performance guidance for auto-sync, checkpoints, and acceptance filters.

- `docs/SWAGGER.md`
  - Added the current index endpoints and request shapes.
  - Documented branch-aware index and search payloads.

- `http/kira-api.http`
  - Corrected single-file indexing endpoint.
  - Added branch variables, Git incremental indexing, manual sync, and index status requests.

## Verification

Run from the project root:

```bash
mvn compile -q
mvn test -q
```
