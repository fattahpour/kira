# Kira — Agent Integration Guide

How to integrate Kira into real coding-agent workflows (Claude Code, Codex CLI, Gemini CLI) and minimize the tokens your agent spends on every retrieval call.

---

## Table of Contents

1. [What Kira Gives Your Agent](#1-what-kira-gives-your-agent)
2. [MCP Tool Reference](#2-mcp-tool-reference)
3. [Token Budget: The Core Mental Model](#3-token-budget-the-core-mental-model)
4. [Tool Selection Decision Tree](#4-tool-selection-decision-tree)
5. [Code Search Patterns](#5-code-search-patterns)
6. [Knowledge / Document Search Patterns](#6-knowledge--document-search-patterns)
7. [Token Minimization Techniques](#7-token-minimization-techniques)
8. [Real-World Agent Scenarios](#8-real-world-agent-scenarios)
9. [Wiring Kira to Your Agent](#9-wiring-kira-to-your-agent)
10. [Anti-Patterns](#10-anti-patterns)

---

## 1. What Kira Gives Your Agent

Kira is an embedded retrieval engine exposed as an MCP server. It provides:

| Capability | How |
|------------|-----|
| Hybrid code search | BM25 + KNN fused with Reciprocal Rank Fusion |
| Knowledge search | Same hybrid engine, domain-filtered to docs/markdown/PDF |
| Code graph | JavaParser AST → callers, callees, Kafka flows, bean graph |
| Answer context | Reranked + compacted text within a hard token budget |
| Incremental index | Git-diff-based reindex; only changed files re-embedded |
| File acceptance filters | `.gitignore`, global filters, and per-repo filters keep noisy files out |

Everything runs in-process, CPU-only, no network, no external database. The agent talks to Kira via MCP stdio or HTTP.

For a focused MCP endpoint setup guide, see:

```text
docs/MCP_ENDPOINT_USAGE.md
```

For every MCP service with parameters, return shapes, and call examples, see:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

For file exclusion recipes before indexing a repo or document folder, see:

```text
docs/FILE_EXCLUSION_GUIDE.md
```

---

## 2. MCP Tool Reference

### `search_code`

Search Java/source chunks by hybrid BM25 + KNN.

```
search_code(query, repo, branch, k)
```

| Parameter | Type | Notes |
|-----------|------|-------|
| `query` | string | Natural language or symbol name |
| `repo` | string \| null | Repo id to filter; null = all repos |
| `branch` | string \| null | Branch label to filter; null = all branches |
| `k` | int | Max results returned |

Returns: `SearchResult` — `hits[]` plus nullable `error`. Each hit has `id`, `path`, `score`, `text` (chunk text), `fqn` (if code), `domain`, `type`.

Always check `error` before treating an empty `hits` list as a clean miss.

---

### `search_knowledge`

Same as `search_code` but domain-locked to `KNOWLEDGE` (markdown, PDF, DOCX, Confluence HTML).

```
search_knowledge(query, repo, branch, k)
```

---

### `semantic_search`

Hybrid search across **both** `CODE` and `KNOWLEDGE` domains with no domain filter.

```
semantic_search(query, repo, branch, k)
```

Use when you do not know which domain holds the answer. Always more expensive than a domain-filtered call for the same `k`.

---

### `keyword_search`

BM25-only keyword search. It skips embedding inference and is best for exact class names, identifiers, FQN fragments, route strings, and config keys.

```
keyword_search(query, repo, domain, k)
```

Returns: `SearchResult` — `hits[]` plus nullable `error`.

Use this before semantic tools when the query is already a concrete name.

---

### `answer_context`

Retrieve, rerank, and compact hits into a single text block that fits within `budgetTokens`.

```
answer_context(query, repo, budgetTokens)
```

| Parameter | Type | Notes |
|-----------|------|-------|
| `query` | string | Natural language question |
| `repo` | string \| null | Repo filter |
| `budgetTokens` | int | Hard ceiling on returned context size |

Returns: `string` — plain text context block, ready to paste into a prompt. The orchestrator retrieves a configurable candidate pool, reranks, and compacts while reserving budget for multiple hits so one large hit cannot consume the whole budget.

---

### `get_symbol`

Look up one symbol by fully qualified name.

```
get_symbol(fqn)
```

Returns: `SymbolView` — `fqn`, `signature`, `javadoc`, `body` (method body or class declaration), direct `callers[]`, direct `callees[]` (all as signatures, not bodies).

This is the most token-efficient tool for direct symbol lookup. Zero retrieval noise. Returned body text is capped at 8000 characters and truncated on a newline when possible.

---

### `find_symbol`

Find candidate symbols when you only know a class name, method name, endpoint fragment, or partial FQN.

```
find_symbol(partialName, type)
```

| Parameter | Type | Notes |
|-----------|------|-------|
| `partialName` | string | Partial class name, method name, endpoint path, or FQN fragment |
| `type` | string \| null | Optional filter: `CLASS`, `METHOD`, `INTERFACE`, `ENDPOINT`, or null |

Returns: `SymbolListResult` — `symbols[]` plus nullable `error`.

Use this before `get_symbol` when the exact FQN is unknown. Symbol lookup uses a lowercase indexed simple-name prefix field, so `PaymentService` and `paymentservice` both work without an expensive leading wildcard scan.

---

### `discover_symbols`

Metadata-only symbol discovery. It returns the same candidate symbol shape as `find_symbol`, but strips snippets so the agent receives only `fqn`, `type`, and `path`.

```
discover_symbols(partialName, type, k)
```

Use this for navigation questions such as "what services exist?" or "find payment-related classes" when snippets are not needed.

---

### `get_callers`

BFS from `fqn` upward through the call graph.

```
get_callers(fqn, depth)
```

Returns: `List<String>` — FQNs of callers, bounded by `depth`. Returns signatures, not bodies.

---

### `get_callees`

BFS from `fqn` downward through the call graph.

```
get_callees(fqn, depth)
```

Returns: `List<String>` — FQNs of methods this symbol calls, bounded by `depth`.

---

### `get_kafka_flow`

Find all producers and consumers registered to a Kafka topic.

```
get_kafka_flow(topic)
```

Returns: `KafkaFlow` — `topic`, `producers[]`, `consumers[]` (each as FQN + signature).

---

### `expand_context`

Walk the code graph from one or more seed FQNs up to `hops` steps and return related symbol signatures.

```
expand_context(fqns, hops, maxResults)
```

| Parameter | Type | Notes |
|-----------|------|-------|
| `fqns` | string | Comma-separated FQNs |
| `hops` | int | 1 = direct neighbors, 2 = two hops |
| `maxResults` | int | 0 uses default 50, otherwise capped at 200 |

Returns: `ExpandedContext` — `seeds`, `hops`, `signatures[]` (all returned as signatures only, never bodies).

---

### `refresh_index`

Trigger a blocking full reindex from an MCP client.

```
refresh_index(repo, repoDir)
```

| Parameter | Type | Notes |
|-----------|------|-------|
| `repo` | string | Repo id to assign to indexed chunks |
| `repoDir` | string | Absolute path to the repo or document folder |

Returns: `IndexStatus` — `docCount`, `version`. On failure, `docCount` is `-1` and `version` includes an error message.

---

### `get_endpoint`

Look up a REST endpoint by HTTP method and path.

```
get_endpoint(method, path)
```

Returns: `EndpointInfo` or `null`. Java Spring mappings are indexed as keys like `GET /api/v1/orders`. Array mappings such as `@GetMapping({"/v1", "/v2"})` use the first path.

---

### `get_bean_graph`

Traverse Spring constructor-injection dependencies.

```
get_bean_graph(name, depth)
```

Returns: `BeanGraph` — seed bean plus dependency edges. The lookup accepts a simple class name or partial FQN and resolves simple dependency names to bean-tagged FQNs when possible.

---

### `get_design_for_symbol`

Find design or documentation chunks related to a code symbol.

```
get_design_for_symbol(fqn)
```

Returns: `SearchResult` — `hits[]` plus nullable `error`.

---

### `get_code_for_doc`

Find code chunks related to a documentation chunk.

```
get_code_for_doc(docId)
```

Returns: `SearchResult` — `hits[]` plus nullable `error`.

---

### `check_spec_vs_impl`

Compare indexed OpenAPI spec endpoints against implemented REST endpoints in the same repo.

```
check_spec_vs_impl(repo)
```

Returns: `SpecImplReport` — `repo`, `unimplemented[]`, `undocumented[]`, `matched[]`, `total`, and nullable `error`.

Endpoint comparison is repo-scoped. Java endpoint nodes carry `REPO:<repo>` tags so a repo's report does not include handlers from other indexed repos.

---

### `index_status`

Check how many documents are indexed and the server version.

```
index_status()
```

Returns: `IndexStatus` — `docCount`, `version`.

---

## 3. Token Budget: The Core Mental Model

Every retrieval call costs tokens in two places:

1. **The tool call itself** — Kira returns text; that text enters your agent's context.
2. **The model's follow-up** — the model processes the context before generating a response.

The goal is: **return exactly enough context, nothing more.**

Kira's token-minimization hierarchy (highest return → lowest):

| Priority | Technique | When to use |
|----------|-----------|-------------|
| 1 | Rerank then truncate | Always. `answer_context` does this automatically. |
| 2 | Use graph tools instead of search | When you already know the FQN. |
| 3 | Domain filter | Separate `search_code` / `search_knowledge` calls instead of `semantic_search`. |
| 4 | Repo + branch filter | Always pass `repo` when you know which repository. |
| 5 | Small `k` | Start with k=5. Increase only when hits are insufficient. |
| 6 | `budgetTokens` cap | Pass to `answer_context`; start at 1500, never exceed 4000 for a single call. |
| 7 | Signatures not bodies | `get_callers`/`get_callees`/`expand_context` return signatures. Only `get_symbol` returns a body, and only for the one symbol you asked for. |
| 8 | Exclude noisy files before indexing | Use `.gitignore` and `accept.exclude` so generated files never enter the retrieval pool. |

---

## 4. Tool Selection Decision Tree

```
Do you know the exact FQN?
├── YES → get_symbol(fqn)
│         Then, if you need neighbors:
│         └── get_callers / get_callees (depth=1 first)
│
└── NO → Do you know part of the symbol name?
          ├── YES → find_symbol(partialName, type)
          │         Then call get_symbol(fqn) on the best candidate.
          │
          └── NO → Do you need a single compact answer?
                    ├── YES → answer_context(query, repo, 1500)
                    │
                    └── NO → Do you know the domain?
                              ├── CODE → search_code(query, repo, branch, k=5)
                              ├── KNOWLEDGE → search_knowledge(query, repo, branch, k=3)
                              └── UNKNOWN → semantic_search(query, repo, branch, k=5)
                                            (most expensive — avoid when possible)
```

---

## 5. Code Search Patterns

### Pattern A: Exact symbol lookup

When you know (or can infer) the FQN from the query, skip search entirely.

```
// Agent intent: "What does PaymentService.charge() do?"
get_symbol("com.example.payments.PaymentService#charge(Order, PaymentMethod)")
```

Returned: signature + javadoc + body + direct callers + direct callees (all as signatures).
Tokens used: ~200–600 depending on method body size.

Compare to running `search_code("PaymentService charge", repo="payments", k=5)`:
- Returns 5 chunks, each up to 512 chars
- Likely includes unrelated overloads, tests, and imports
- Tokens used: ~1500–3000

**Use `get_symbol` when you know the FQN. It is 3–10× cheaper.**

---

### Pattern B: Narrow then drill

When you do not know the FQN, retrieve a small hit list first, extract the FQN from the top hit, then use `get_symbol`.

Step 1: Retrieve candidates
```
find_symbol("PaymentService", type="CLASS")
```

Step 2: Extract FQN from the best symbol candidate, then:
```
get_symbol("com.example.payments.PaymentService#charge(Order, PaymentMethod)")
```

Total tokens: ~100 (symbol list) + ~400 (symbol) = ~500.
Direct `search_code(k=10)` without drill: ~4000+ tokens, with noise.

---

### Pattern C: Call graph traversal

When you need to understand impact of changing a method.

```
// Who calls OrderService.submit()?
get_callers("com.example.orders.OrderService#submit(OrderRequest)", depth=1)

// What does OrderService.submit() call?
get_callees("com.example.orders.OrderService#submit(OrderRequest)", depth=1)
```

Both return FQN lists (signatures only). Then call `get_symbol` only on the ones relevant to the task.

For wide impact analysis go depth=2, but expect the list to grow quadratically. Prefer depth=1 unless explicitly investigating transitive dependencies.

---

### Pattern D: Feature area exploration

When you need to understand a subsystem without knowing specific FQNs.

Step 1: Search broadly, small k
```
search_code("authentication token validation", repo="auth-service", branch="main", k=5)
```

Step 2: Pick the most relevant hit, extract its FQN, then expand:
```
expand_context("com.example.auth.TokenValidator#validate(String)", hops=1)
```

Step 3: From the expanded signatures, call `get_symbol` on the 1–2 most relevant.

This pattern costs ~300 + ~200 + 2×400 = ~1300 tokens vs. a raw `semantic_search(k=20)` which would cost ~8000+ tokens.

---

### Pattern E: Kafka flow discovery

```
// Which services produce to "order-events"?
get_kafka_flow("order-events")
```

Returns producers and consumers as FQNs. Follow up with `get_symbol` only on relevant ones.

---

## 6. Knowledge / Document Search Patterns

### Pattern F: Specific doc question

When the agent needs to answer a question from documentation.

```
answer_context("what is the retry policy for payment failures", repo="payments", budgetTokens=1500)
```

`answer_context` retrieves across CODE and KNOWLEDGE, reranks to surface the best chunks, and compacts to 1500 tokens. The agent gets one clean context block — no raw hit list to process.

---

### Pattern G: ADR / architecture lookup

Architecture decision records are tagged `type=ADR` in the index.

```
search_knowledge("why did we choose Lucene over a vector database", repo="kira", branch="main", k=3)
```

Return k=3 for ADR queries — ADRs are dense and one doc usually answers the question.

---

### Pattern H: API spec vs implementation check

Use the endpoint comparison tool when OpenAPI files and Java controllers are indexed for the same repo.

```
check_spec_vs_impl("orders-service")
```

The result lists endpoints that are in the spec but not implemented, implemented but missing from the spec, and matched. Check `error` before trusting an empty result.

Use `get_endpoint(method, path)` when the task is about one known route.

---

### Pattern I: Cross-domain question

When you do not know if the answer is in code or docs.

```
answer_context("how does session expiry work", repo="auth-service", budgetTokens=2000)
```

`answer_context` searches both domains internally, reranks, and returns the best cross-domain context. Your agent sees one block, not two separate hit lists.

---

## 7. Token Minimization Techniques

### Technique 1: Always pass `repo`

```
// BAD — searches all indexed repos
search_code("UserRepository findByEmail", repo=null, branch=null, k=5)

// GOOD — narrows Lucene scan to one repo's segments
search_code("UserRepository findByEmail", repo="auth-service", branch=null, k=5)
```

Filtering at the Lucene level saves embedding computation and cuts the candidate pool before scoring. Pass `repo` on every call unless the task is explicitly cross-repo.

---

### Technique 2: Pass `branch` when working in feature branches

```
// Working on feature/payment-v2 — only return code from that branch
search_code("StripeGateway charge", repo="payments", branch="feature/payment-v2", k=5)
```

Without the branch filter, results from `main` and other branches mix in and add noise. Each noisy hit is context the agent must process.

---

### Technique 3: Domain filter saves ~40% tokens vs. `semantic_search`

```
// BAD — searches code + docs, returns mixed hits
semantic_search("authentication middleware", repo="gateway", branch="main", k=5)

// GOOD — you know the answer is in code
search_code("authentication middleware", repo="gateway", branch="main", k=5)

// GOOD — you know the answer is in docs
search_knowledge("authentication middleware", repo="gateway", branch="main", k=3)
```

`semantic_search` is only appropriate when you genuinely cannot predict the domain.

---

### Technique 4: Start with k=3–5, not k=10+

The top-3 hits from a well-specified hybrid query usually contain the answer. Every extra hit is tokens the model must skip over.

```
// First try
search_code("OrderValidator validate rules", repo="orders", branch="main", k=3)

// If hits are insufficient, retry with k=5
// Only go to k=10 when the subsystem is genuinely large
```

Rule of thumb: k=3 for precise symbol queries, k=5 for feature-area queries, k=10 only for exploratory mapping.

---

### Technique 5: `answer_context` for single-answer questions

When the agent needs to answer one question and move on, `answer_context` is always cheaper than `search_*` + manual processing:

```
// BAD — agent gets 5 hits and must reason over all of them
search_code("how does the circuit breaker reset", repo="gateway", branch="main", k=5)

// GOOD — agent gets one compacted answer block
answer_context("how does the circuit breaker reset", repo="gateway", budgetTokens=1200)
```

The difference: 5 raw hits at 512 chars each ≈ 2560 chars ≈ 640 tokens in the model context. `answer_context` with `budgetTokens=1200` caps at ~300 tokens of actual useful content (chars/4 = tokens). Saves ~340 tokens per call. At 100 calls per session that is 34,000 tokens saved.

---

### Technique 6: Tune `budgetTokens` to the task

| Task type | Recommended `budgetTokens` |
|-----------|---------------------------|
| Quick fact lookup | 800 |
| Method explanation | 1200 |
| Feature-area overview | 2000 |
| Architectural question | 3000 |
| Complex multi-file task | 4000 |

Never set `budgetTokens` to a large number "just in case." The orchestrator fills the budget — bigger budget = more tokens consumed.

---

### Technique 7: Graph tools return signatures, not bodies

`get_callers`, `get_callees`, and `expand_context` return FQN + signature strings. A signature is typically 1–3 lines. A method body is 10–200 lines.

```
// Returns signatures of all direct callers — ~200 tokens total
get_callers("com.example.PaymentService#charge(Order)", depth=1)

// Returns full body of ONE method — ~300–800 tokens
get_symbol("com.example.PaymentService#charge(Order)")
```

Pattern: use graph tools to find what is relevant, then call `get_symbol` only for the 1–2 methods you actually need to read.

---

### Technique 8: Incremental indexing keeps the index fresh without full reindex

When an agent writes new code and then immediately queries it, the index will miss the new file unless reindexed. Use the incremental endpoint:

```bash
curl -X POST http://localhost:8080/api/v1/index/incremental \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "my-service",
    "repoDir": "/home/user/projects/my-service",
    "fromSha": "HEAD~1",
    "toSha": "HEAD",
    "branch": "feature/new-feature"
  }'
```

Incremental indexing processes only git-diff'd blobs. For a small commit (5–10 files), this completes in under a second. Full reindex of a large repo can take minutes and should not be triggered per-agent-call.

### Technique 9: Keep noisy files out before retrieval

Do not make the agent filter out build output, vendored dependencies, generated clients, or local secrets after retrieval. Exclude them before indexing.

Use:

- `.gitignore` for normal untracked local files.
- `kira.accept.exclude` for tracked files or project-specific noise.
- `kira.repos[].accept` when only one configured repo needs special rules.

After changing filters, restart Kira and run a full reindex or `refresh_index(repo, repoDir)` from MCP. Editing config alone does not rewrite existing index entries.

---

## 8. Real-World Agent Scenarios

### Scenario 1: Fix a bug in a known method

**Goal:** Agent is told "fix the NPE in `InvoiceService.generate()`."

**Token-optimal strategy:**

```
// Step 1: Get the exact method
get_symbol("com.example.billing.InvoiceService#generate(Customer, Period)")

// Step 2: Check what it calls to understand data flow
get_callees("com.example.billing.InvoiceService#generate(Customer, Period)", depth=1)

// Step 3: If a callee is suspicious, look it up
get_symbol("com.example.billing.LineItemBuilder#build(Order)")
```

Total: ~600 + ~150 + ~400 = ~1150 tokens.

**Anti-pattern:** Running `search_code("InvoiceService generate NPE", repo=..., k=10)` returns 10 chunks including test files, utility methods, and unrelated billing code. ~4000 tokens, most of which is noise.

---

### Scenario 2: Write a unit test for a service method

**Goal:** Agent needs to generate a JUnit 5 test for `UserService.register(RegisterRequest)`.

```
// Get the method signature, body, and its direct callees
get_symbol("com.example.user.UserService#register(RegisterRequest)")

// Get what it calls so the test can mock those dependencies
get_callees("com.example.user.UserService#register(RegisterRequest)", depth=1)
```

The agent now has: the exact signature it must test, the body it must cover, and the list of dependencies it must mock. No search noise.

Total: ~700 + ~200 = ~900 tokens.

---

### Scenario 3: Understand the checkout flow end-to-end

**Goal:** Agent is implementing a new promotion system and needs to understand the checkout flow before modifying it.

```
// Step 1: Find the entry point
search_code("checkout submit order controller", repo="orders-service", branch="main", k=3)

// Step 2: From the top hit, get the controller method
get_symbol("com.example.orders.CheckoutController#submit(CheckoutRequest)")

// Step 3: Walk two hops of callees from the controller
expand_context("com.example.orders.CheckoutController#submit(CheckoutRequest)", hops=2)

// Step 4: From the expanded signatures, select 2-3 key service methods
get_symbol("com.example.orders.OrderService#createOrder(CheckoutRequest)")
get_symbol("com.example.payments.PaymentGateway#charge(Order)")
```

Total: ~300 (search) + ~600 + ~800 (expand) + 2×600 = ~2900 tokens.

Alternative using `semantic_search(k=20)`: ~8000–12000 tokens with many unrelated hits.

---

### Scenario 4: Answer an architecture question from docs

**Goal:** Agent is asked "why does Kira use Lucene instead of a vector database?"

```
answer_context("why Lucene instead of vector database", repo="kira", budgetTokens=1500)
```

The orchestrator finds the ADR in the knowledge index, reranks against the question, and returns a compact answer. The agent produces a direct answer without seeing the raw documents.

Total: ~375 tokens (1500 chars / 4 = 375 tokens).

---

### Scenario 5: Find all producers of a Kafka topic

**Goal:** Agent is debugging missing events on the `payment-completed` topic.

```
// Who produces to this topic?
get_kafka_flow("payment-completed")

// Follow up on the most relevant producer
get_symbol("com.example.payments.PaymentService#completePayment(Payment)")
```

Total: ~100 (flow) + ~500 (symbol) = ~600 tokens.

Alternative using `search_code("payment-completed topic producer", k=10)`: ~4000 tokens, likely missing some producers if they use a constant for the topic name.

---

### Scenario 6: Cross-repo search for shared auth logic

**Goal:** Agent is working across a gateway service and an auth service.

```
// repo=null searches all indexed repos
search_code("JWT token validation expiry", repo=null, branch=null, k=5)
```

Returns hits from both repos in one call. Follow up with `get_symbol` on the relevant ones.

Only omit `repo` when the question is genuinely cross-repo. For single-repo tasks, always filter.

---

### Scenario 7: Refactoring — impact analysis before changing a method

**Goal:** Agent is about to change `OrderRepository.findByCustomer()` and needs to know the blast radius.

```
// Who calls this method?
get_callers("com.example.orders.OrderRepository#findByCustomer(Long)", depth=2)
```

Returns all direct and transitive callers up to 2 hops as FQN signatures. The agent can now list affected call sites without reading any bodies.

If the caller list is long (>20), check depth=1 first and only widen to depth=2 if needed.

---

### Scenario 8: Check if an API spec matches the implementation

**Goal:** Agent is validating that the OpenAPI spec for `POST /invoices` matches the actual controller.

```
// Step 1: Compare every indexed spec endpoint against Java handlers in this repo
check_spec_vs_impl("billing-service")

// Step 2: Inspect one route if needed
get_endpoint("POST", "/invoices")

// Step 3: If the endpoint result includes a handler FQN, get the full method
get_symbol("com.example.billing.InvoiceController#createInvoice(CreateInvoiceRequest)")
```

One repo-scoped comparison + one endpoint lookup + one exact symbol lookup. Total: usually under ~1000 tokens.

Running `semantic_search("POST /invoices", k=10)` to get the same information: ~5000 tokens, mixed results from test files, error handlers, and unrelated controllers.

---

### Scenario 9: Generate a feature without breaking existing behavior

**Goal:** Agent adds a discount field to `OrderRequest` and needs to understand all affected serialization, validation, and persistence paths.

```
// Step 1: Find the OrderRequest class
get_symbol("com.example.orders.OrderRequest")

// Step 2: Find all code that uses OrderRequest
get_callers("com.example.orders.OrderRequest", depth=1)

// Step 3: For each affected caller, get its callees to find persistence/serialization
expand_context("com.example.orders.OrderService#createOrder(OrderRequest)", hops=2)

// Step 4: Check if there is a schema doc or migration guide
search_knowledge("OrderRequest schema validation rules", repo="orders-service", branch="main", k=2)
```

The agent has: the class definition, all call sites, transitive flow, and any documented schema rules — enough to add the field safely.

---

### Scenario 10: Agent loop — repeated queries during a long coding session

In a long session an agent may call search tools 50–100 times. Token drift compounds.

**Rule:** Set a per-call token budget and enforce it in your system prompt.

Example system prompt addition:
```
When using Kira:
- Always pass repo and branch.
- Use get_symbol(fqn) when you know the class and method name.
- Use search_code or search_knowledge with k ≤ 5.
- Use answer_context(budgetTokens=1500) for open-ended questions.
- Never use semantic_search unless domain is unknown.
- Never call search with k > 10.
```

At 50 calls per session:
- Naive usage: 50 × 3000 tokens avg = 150,000 tokens
- Disciplined usage: 50 × 800 tokens avg = 40,000 tokens
- Saving: 110,000 tokens per session

---

## 9. Wiring Kira to Your Agent

### Claude Code (stdio MCP)

Add to `~/.claude/settings.json` (or project `.claude/settings.json`):

```json
{
  "mcpServers": {
    "kira": {
      "command": "java",
      "args": [
        "-jar",
        "/home/user/projects/kira/target/ai-retrieval-0.1.0-SNAPSHOT.jar"
      ],
      "env": {
        "KIRA_DATA_DIR": "/home/user/.kira/data"
      }
    }
  }
}
```

Claude Code connects via stdio. Kira's `@Tool` methods become available as MCP tools automatically.

After connecting, verify:

```
index_status()
```

Expected: `{"docCount": N, "version": "0.1.0"}`.

---

### Codex CLI

Codex uses MCP over stdio. Add to your Codex config:

```json
{
  "mcp": {
    "servers": {
      "kira": {
        "command": "java",
        "args": ["-jar", "/path/to/ai-retrieval-0.1.0-SNAPSHOT.jar"],
        "transport": "stdio"
      }
    }
  }
}
```

Or start Kira in HTTP mode and point Codex at the SSE endpoint:

```bash
java -jar ai-retrieval-0.1.0-SNAPSHOT.jar \
  --spring.ai.mcp.server.stdio=false \
  --spring.ai.mcp.server.type=ASYNC \
  --server.port=8080
```

Codex MCP config:
```json
{
  "mcp": {
    "servers": {
      "kira": {
        "url": "http://localhost:8080/sse",
        "transport": "sse"
      }
    }
  }
}
```

---

### Gemini CLI

Gemini CLI supports MCP via HTTP SSE. Start Kira in async HTTP mode (same as above), then add to your Gemini settings:

```json
{
  "tools": {
    "mcp": {
      "kira": {
        "url": "http://localhost:8080/sse"
      }
    }
  }
}
```

---

### Configuring Repos for Auto-Sync

Add repos to `application.yml` so Kira indexes automatically on a schedule:

```yaml
kira:
  repos:
    - id: payments-service
      path: /home/user/projects/payments-service
      branches:
        mode: SINGLE
        tracked:
          - main
      auto-sync:
        enabled: true
        interval-seconds: 300
      accept:
        include:
          - "src/**/*.java"
          - "docs/**/*.md"
          - "openapi/**/*.yml"
        exclude:
          - "src/test/**"

    - id: auth-service
      path: /home/user/projects/auth-service
      branches:
        mode: MULTI
        tracked:
          - main
          - "feature/*"
      auto-sync:
        enabled: true
        interval-seconds: 600
```

With this config:
- `payments-service` syncs `main` every 5 minutes
- `auth-service` syncs `main` and any `feature/*` branch every 10 minutes
- Kira respects `.gitignore` by default (`respect-gitignore: true`)
- Only files matching the `accept` globs are indexed

---

## 10. Anti-Patterns

### Anti-pattern 1: Using `semantic_search` as the default tool

```
// WRONG — unfiltered cross-domain search for something in code
semantic_search("UserService register validation", repo="auth", branch="main", k=10)

// CORRECT
search_code("UserService register validation", repo="auth", branch="main", k=5)
```

`semantic_search` scans both domains and returns up to k mixed results. Use it only when domain is genuinely unknown.

---

### Anti-pattern 2: Large `k` on the first try

```
// WRONG — agent retrieves 20 chunks "just in case"
search_code("authentication", repo="gateway", branch="main", k=20)

// CORRECT — start small, widen only if needed
search_code("authentication filter chain", repo="gateway", branch="main", k=5)
```

The reranker already surfaces the best hits at the top. k=20 rarely returns better results than k=5 for a precise query — it just adds noise and tokens.

---

### Anti-pattern 3: Searching when you know the FQN

```
// WRONG — agent knows the class name and method, still runs search
search_code("PaymentService charge", repo="payments", branch="main", k=5)

// CORRECT — go directly to the symbol
get_symbol("com.example.payments.PaymentService#charge(Order, PaymentMethod)")
```

If the FQN is available (from previous search hits, from the error message, from the codebase file list), always use `get_symbol`. It is zero-noise and typically 5× cheaper.

---

### Anti-pattern 4: Omitting `repo` on single-repo tasks

```
// WRONG — scans all repos even though the task is in payments-service
search_code("InvoiceBuilder create", repo=null, branch=null, k=5)

// CORRECT
search_code("InvoiceBuilder create", repo="payments-service", branch="main", k=5)
```

Omitting `repo` multiplies the candidate pool by the number of indexed repos and increases scoring noise.

---

### Anti-pattern 5: Calling `get_callees` at depth=3 immediately

```
// WRONG — depth=3 can return hundreds of FQNs
get_callees("com.example.orders.OrderService#createOrder(OrderRequest)", depth=3)

// CORRECT — start at depth=1, go deeper only if needed
get_callees("com.example.orders.OrderService#createOrder(OrderRequest)", depth=1)
```

Each extra depth level multiplies the result set. At depth=3 in a large codebase, `get_callees` can return 500+ signatures. Start at 1.

---

### Anti-pattern 6: Using `answer_context` with a huge budget for a simple question

```
// WRONG — 8000 tokens for "what is the default port"
answer_context("what is the default server port", repo="my-service", budgetTokens=8000)

// CORRECT — fact lookup needs very little context
answer_context("what is the default server port", repo="my-service", budgetTokens=800)
```

`budgetTokens` is a ceiling, not a target — the orchestrator fills it. Larger budget = more content returned = more tokens consumed by the model.

---

### Anti-pattern 7: Full reindex on every agent call

```bash
# WRONG — triggers a full filesystem walk + re-embed of all files
curl -X POST /api/v1/index/full -d '{"repo": "my-service", "repoDir": "..."}'

# CORRECT — only reindex what changed
curl -X POST /api/v1/index/incremental -d '{
  "repo": "my-service", "repoDir": "...",
  "fromSha": "HEAD~1", "toSha": "HEAD", "branch": "main"
}'
```

Full reindex on a 10,000-file repo takes 2–10 minutes depending on embedding speed. Use it only on initial setup or after changing the embedding model. Use incremental for routine syncs.

---

## Quick Reference Card

| I want to... | Use | Typical tokens |
|--------------|-----|----------------|
| Read one method I know by FQN | `get_symbol(fqn)` | 200–600 |
| Find callers of a method | `get_callers(fqn, depth=1)` | 50–200 |
| Find callees of a method | `get_callees(fqn, depth=1)` | 50–200 |
| Find an FQN from a partial name | `find_symbol(partialName, type)` | 50–150 |
| Find symbol names without snippets | `discover_symbols(partialName, type, k=10)` | 30–100 |
| Search an exact identifier fast | `keyword_search(q, repo, domain, k=5)` | 200–800 |
| Look up one REST route | `get_endpoint(method, path)` | 50–200 |
| Compare OpenAPI vs Java endpoints | `check_spec_vs_impl(repo)` | 100–500 |
| Explore Spring bean dependencies | `get_bean_graph(name, depth=1)` | 100–500 |
| Explore a feature area | `search_code(q, repo, branch, k=5)` | 500–1500 |
| Get a compact answer from docs or code | `answer_context(q, repo, 1500)` | 375 |
| Find Kafka producers/consumers | `get_kafka_flow(topic)` | 50–150 |
| Explore related symbols from a seed | `expand_context(fqns, hops=1, maxResults=25)` | 200–800 |
| Search docs/markdown/PDF | `search_knowledge(q, repo, branch, k=3)` | 300–900 |
| Search both code and docs | `semantic_search(q, repo, branch, k=5)` | 800–2500 |
| Refresh an index from MCP | `refresh_index(repo, repoDir)` | 10 |
| Check index health | `index_status()` | 10 |
