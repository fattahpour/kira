# Kira — Respect `.gitignore` During Indexing Implementation

Implemented `docs/superpowers/plans/2026-06-17-gitignore-respect.md`.

## Changes

- Added `kira.respect-gitignore` configuration, defaulting to `true`.
- Added `GitIgnoreFilter`, backed by JGit `IgnoreNode`, for `.gitignore` and `.git/info/exclude` matching.
- Applied gitignore filtering to full repository indexing before acceptance filters and source classification.
- Applied gitignore filtering to single-file indexing before acceptance filters.
- Left Git incremental indexing unchanged because it is driven by tracked Git diffs.
- Added unit coverage for disabled filtering, root patterns, directory patterns, nested negation, missing gitignore files, and backslash path normalization.
- Updated user-facing docs for the new configuration behavior.

## Verification

```bash
mvn compile -q && mvn test -q
```

Result: passed.
