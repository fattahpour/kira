# Codex Prompt: Review Fixes Implementation

## Instructions for Codex

You are implementing bug fixes to the Kira project — a Spring Boot 3.5 / Java 21 local AI code+knowledge retrieval platform.

Work in: `<kira-install-dir>`

Follow the plan at: `docs/superpowers/plans/2026-06-17-review-fixes.md`

**Implement every task in order (Task 1 through Task 6). For each task:**
1. Write the failing test(s) exactly as shown in the plan
2. Run the test to confirm it fails
3. Implement the code change exactly as shown
4. Run the test to confirm it passes
5. Run the full verification step listed in the task
6. Move to the next task

**Do not skip tasks or combine them.**

After all 6 tasks, run:
```bash
mvn test -q
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS`

Then write the report to:
`docs/superpowers/implementation/2026-06-17-review-fixes-implementation.md`

---

## Context: What this fixes

6 bugs found in code review of the search-token-gaps implementation:

| Task | Bug | Files changed |
|---|---|---|
| 1 | MarkdownParser: single paragraph >4000 chars never split; chunk IDs collide between split sections and headings named `Foo-N` | `MarkdownParser.java` |
| 2 | SearchHit: `snippet` component stores truncated 500-char text at load time; reranker can only score the truncated version | `SearchHit.java` |
| 3 | RetrievalOrchestrator: `simpleName()` misses `/` separator so endpoint FQNs like `"GET /api/items"` are not stripped; `hybridRerank()` passes `hit.snippet()` (truncated) not `hit.text()` (full) to reranker | `RetrievalOrchestrator.java` |
| 4 | HybridSearch: BM25 and KNN pools hardcoded to `this.candidateK`; when `k > 2*candidateK`, caller silently receives fewer than `k` results | `HybridSearch.java` |
| 5 | ContextCompactor: `available` floored at `MIN_TOKENS_PER_HIT=20` even when budget is exhausted; output can overshoot budget by up to 19 tokens | `ContextCompactor.java` |
| 6 | JavaSourceParser: `symbols()` stores annotation names with `@` prefix (`@Service`); `SearchFilter.symbol` callers must know to include `@` or get zero results | `JavaSourceParser.java`, `NrtLuceneSearcherTest.java` |

---

## Key architectural rules (do not violate)

- Layering: `ingest → embed → index/graph → retrieve → (mcp | api)`. Engine packages (`embed`, `index`, `graph`, `retrieve`) must stay framework-free — no Spring annotations.
- No Python. No external DB. All offline.
- Tasks must be done in order. **Task 3 depends on Task 2** — it uses `hit.text()` which only exists after the SearchHit rename in Task 2.

---

## Critical implementation notes

### Task 1 — MarkdownParser
- Unsplit sections: ID stays `path#slug` (no change to existing behavior).
- Split sections: use `path#slug:N` (colon separator). `slugify()` replaces `:` with `-`, so no heading slug can produce a colon — zero collision risk.
- Oversized single paragraph fix: after the standard flush-current block, add a check `if (paragraph.length() > MAX_SECTION_CHARS)` and split at word/char boundaries in a `while` loop.

### Task 2 — SearchHit rename
- Record component name changes: `snippet` → `text`.
- Add `public String snippet()` instance method (word-boundary truncation at 500 chars).
- `fromDocument()`: change `snippet(doc.get("text"))` to just `doc.get("text")` (store full text).
- Remove the `private static String snippet(String text)` helper — replaced by the instance method.
- All existing test constructors use the 9th positional arg for text content — still correct after rename. Short strings: `snippet()` returns unchanged. Long strings: `snippet()` truncates, `text()` returns full.

### Task 3 — RetrievalOrchestrator
- `simpleName()`: add `fqn.lastIndexOf('/')` to the `Math.max(...)` chain — same pattern already used in `LuceneIndexer`.
- `hybridRerank()`: change `reranker.score(query, hit.snippet())` to `reranker.score(query, hit.text() != null ? hit.text() : "")`.

### Task 4 — HybridSearch
- `search()`: add `int fetchK = Math.max(k, candidateK);` and pass `fetchK` to both `searcher.bm25(...)` and `searcher.knn(...)`. Final `.limit(k)` stays as-is.

### Task 5 — ContextCompactor
- Move `int remaining = budgetTokens - used;` to top of loop.
- Add `if (remaining <= 0) break;` immediately after.
- Change `available` calculation to: `int available = Math.min(remaining, Math.max(MIN_TOKENS_PER_HIT, remaining - reserved));`
- Remove the dead-code guard `if (available <= 0) break;` (now unreachable given the early exit above).

### Task 6 — JavaSourceParser
- `symbols()`: remove the `"@" +` prefix — use `AnnotationExpr::getNameAsString` directly.
- Update `NrtLuceneSearcherTest.java`: find `new SearchFilter(null, null, null, null, null, "@Service")` and change to `new SearchFilter(null, null, null, null, null, "Service")`.

---

## Test commands

Each task specifies its own test command. Global suite:
```bash
mvn test -q
```

Run from: `<kira-install-dir>`

This is NOT a git repository. Skip git commit steps.

---

## Report template

After completing all tasks, write:

**`docs/superpowers/implementation/2026-06-17-review-fixes-implementation.md`**

```markdown
# Review Fixes Implementation Report

Source plan: `docs/superpowers/plans/2026-06-17-review-fixes.md`

## Implemented

[One bullet per task]

## Key Files

[All modified files]

## Tests Added or Updated

[All test files changed]

## Verification

Run: `mvn test -q`
Expected: BUILD SUCCESS

## Completed Verification On 2026-06-17

- `mvn test -q` result: [paste output]
- Any deviations from plan: [list or "none"]
```
