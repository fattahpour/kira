# Codex Prompt: Search Token Gaps Implementation

## Instructions for Codex

You are implementing a series of bug fixes and improvements to the Kira project — a Spring Boot 3.5 / Java 21 local AI code+knowledge retrieval platform.

Work in: `<kira-install-dir>`

Follow the plan at: `docs/superpowers/plans/2026-06-17-search-token-gaps.md`

**Implement every task in order (Task 1 through Task 13). For each task:**
1. Write the failing test(s) exactly as shown in the plan
2. Run the test to confirm it fails
3. Implement the code change exactly as shown
4. Run the test to confirm it passes
5. Run the full set listed in the task's verification step
6. Commit with the exact commit message from the plan

**Do not skip tasks or combine them. Each task must produce a passing commit before moving to the next.**

After all 13 tasks, run:
```bash
mvn test -q
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`

Then write a report to:
`docs/superpowers/implementation/2026-06-17-search-token-gaps-implementation.md`

---

## Context: What this fixes

14 gaps in Kira's search/retrieval layer that waste tokens or hurt retrieval quality:

| Task | Gap | Files changed |
|---|---|---|
| 1 | G14 — ApiSpecParser missing REPO tag on endpoint graph nodes | `ApiSpecParser.java` |
| 2 | G8 — ApiSpecParser chunk text only has FQN + summary, no param/response info | `ApiSpecParser.java` |
| 3 | G9 — MarkdownParser stores entire large sections as single chunks | `MarkdownParser.java` |
| 4 | G10 — Chunk.symbols never populated by JavaSourceParser, never indexed in Lucene | `JavaSourceParser.java`, `LuceneIndexer.java`, `SearchFilter.java`, `NrtLuceneSearcher.java` |
| 5 | G1 — SearchHit.snippet() cuts at char boundary at 300 chars, reranker only sees 300 | `SearchHit.java` |
| 6 | G11 — CANDIDATE_K=50 hardcoded in two places, not configurable | `ApplicationProps.java`, `application.yml`, `HybridSearch.java`, `RetrievalOrchestrator.java` |
| 7 | G6+G7 — getDesignDocs uses raw FQN as query; both cross-domain methods skip reranker | `RetrievalOrchestrator.java` |
| 8 | G13 — checkSpecVsImpl silently caps at 500 ops, no total count in report | `SpecImplReport.java`, `RetrievalOrchestrator.java`, `McpTools.java` |
| 9 | G12 — No BM25-only MCP tool, all searches force ONNX embedding | `McpTools.java` |
| 10 | G2 — No metadata-only search tool, all tools return 500-char snippets | `McpTools.java` |
| 11 | G3 — expand_context BFS uncapped, hops=2 can return 100+ signatures | `GraphQueries.java`, `McpTools.java` |
| 12 | G4 — ContextCompactor first hit can consume entire token budget | `ContextCompactor.java` |
| 13 | G5 — get_symbol body has no size cap, 200-line method = ~5000 tokens | `McpTools.java` |

---

## Key architectural rules (do not violate)

- Layering: `ingest → embed → index/graph → retrieve → (mcp | api)`. Engine packages (`embed`, `index`, `graph`, `retrieve`) must stay framework-free — no Spring annotations.
- No Python. No external DB. All offline.
- `McpTools` methods are annotated `@Tool` for Spring AI MCP registration.
- After Tasks 9 and 10, total registered MCP tools = 19 (was 17 before this plan).
- `SearchFilter` compact constructors: keep all existing overloads when adding `symbol` field in Task 4. Existing callers use 4-arg and 5-arg forms.
- `ApplicationProps` is a `@ConfigurationProperties` record. New fields `candidateK` and `specMaxOps` require corresponding entries in `application.yml` with defaults `50` and `200`.
- `HybridSearch` keeps 2-arg constructor `(searcher, embeddingModel)` as default (candidateK=50) for tests that don't use config.

---

## Test commands

Each task specifies its own test command. Global suite:
```bash
mvn test -q
```

Run from: `<kira-install-dir>`

This is NOT a git repository. Skip git commit steps if `git commit` fails — just implement and test.

---

## Report template

After completing all tasks, write this file:

**`docs/superpowers/implementation/2026-06-17-search-token-gaps-implementation.md`**

```markdown
# Search Token Gaps Implementation Report

Source plan: `docs/superpowers/plans/2026-06-17-search-token-gaps.md`

## Implemented

[List what was implemented, one bullet per task]

## Key Files

[List all modified files]

## Tests Added or Updated

[List test files changed]

## Verification

Run: `mvn test -q`
Expected: BUILD SUCCESS

## Completed Verification On 2026-06-17

- `mvn test -q` result: [paste output]
- Tools registered: [count]
- Any deviations from plan: [list or "none"]
```
