# Kira MCP Tool References

This reference lists every Kira MCP tool, what it is for, how to call it, and what shape to expect back.

Use this with:

- Codex
- Claude Code
- Gemini CLI
- GitHub Copilot in VS Code
- OpenCode
- Any MCP-compatible client

## Before Calling Tools

MCP tools search Kira's existing local index. Index at least one repo or document folder first.

Check the index:

```text
index_status()
```

Use a specific `repo` whenever possible. Use `branch` when you indexed branch-aware chunks.

For this repo, most examples use:

```text
repo="kira"
branch="main"
```

## Return Shapes

Search-style tools return:

```json
{
  "hits": [],
  "error": null
}
```

Check `error` before treating an empty `hits` list as a clean miss.

Symbol discovery tools return:

```json
{
  "symbols": [],
  "error": null
}
```

Spec comparison returns:

```json
{
  "repo": "kira",
  "unimplemented": [],
  "undocumented": [],
  "matched": [],
  "total": 0,
  "error": null
}
```

## Tool Summary

| Tool | Use When |
| --- | --- |
| `index_status()` | Confirm Kira can see indexed data |
| `search_code(query, repo, branch, k)` | Search code with hybrid BM25 + vector reranking |
| `search_knowledge(query, repo, branch, k)` | Search docs and knowledge files |
| `semantic_search(query, repo, branch, k)` | Search code and docs together |
| `keyword_search(query, repo, domain, k)` | Search exact names quickly without embeddings |
| `answer_context(query, repo, budgetTokens)` | Get compact context for final LLM reasoning |
| `find_symbol(partialName, type)` | Find symbols with snippets when exact FQN is unknown |
| `discover_symbols(partialName, type, k)` | Find symbols without snippets to save tokens |
| `get_symbol(fqn)` | Read one Java symbol body and direct neighbors |
| `get_callers(fqn, depth)` | Find callers of a Java symbol |
| `get_callees(fqn, depth)` | Find callees of a Java symbol |
| `expand_context(fqns, hops, maxResults)` | Walk the code graph around seed FQNs |
| `get_kafka_flow(topic)` | Find Kafka producers and consumers |
| `get_endpoint(method, path)` | Look up one REST endpoint handler |
| `get_bean_graph(name, depth)` | Explore Spring constructor-injection dependencies |
| `get_design_for_symbol(fqn)` | Find docs related to a code symbol |
| `get_code_for_doc(docId)` | Find code related to a documentation chunk |
| `check_spec_vs_impl(repo)` | Compare OpenAPI specs with Java endpoints |
| `refresh_index(repo, repoDir)` | Trigger a blocking full reindex from MCP |

## Health And Indexing

### `index_status()`

Use this before search, especially when a client has just started.

```text
index_status()
```

Returns:

```json
{
  "totalDocs": 1250,
  "serverVersion": "0.1.0",
  "repos": [
    {
      "repo": "kira",
      "branch": "main",
      "codeDocs": 900,
      "knowledgeDocs": 350,
      "totalDocs": 1250,
      "lastSha": "abc1234",
      "lastSyncAt": "2026-06-18T10:30:00Z",
      "indexing": false
    }
  ],
  "anyIndexing": false
}
```

### `refresh_index(repo, repoDir)`

Triggers a blocking full reindex from an MCP client.

```text
refresh_index(
  repo="kira",
  repoDir="/home/example/projects/kira"
)
```

Use this after changing file filters, first-time indexing, or large non-Git document folder changes. For normal Git changes, prefer the REST incremental endpoint or configured auto-sync.

## Search Tools

### `search_code(query, repo, branch, k)`

Searches indexed code chunks using hybrid BM25 + vector search with reranking.

```text
search_code(
  query="RetrievalOrchestrator hybrid search",
  repo="kira",
  branch="main",
  k=5
)
```

Use for:

- "Where is this behavior implemented?"
- "Find the service that handles X"
- "Show code around this feature"

### `search_knowledge(query, repo, branch, k)`

Searches docs and knowledge files.

```text
search_knowledge(
  query="how to exclude files from indexing",
  repo="kira",
  branch="main",
  k=5
)
```

Use for:

- Markdown docs
- PDF, DOCX, HTML, text, YAML, and JSON knowledge files
- Tutorials, policies, design notes, and setup guides

### `semantic_search(query, repo, branch, k)`

Searches both code and knowledge domains.

```text
semantic_search(
  query="how does Kira decide what files to index",
  repo="kira",
  branch="main",
  k=8
)
```

Use only when the answer might be in both code and docs. If you already know the domain, prefer `search_code` or `search_knowledge`.

### `keyword_search(query, repo, domain, k)`

BM25-only search. It skips embedding inference and is faster for exact text.

```text
keyword_search(
  query="FullReindexService",
  repo="kira",
  domain="CODE",
  k=5
)
```

Domain can be:

- `"CODE"`
- `"KNOWLEDGE"`
- `null`

Use for:

- Class names
- Method names
- FQN fragments
- Route strings
- Config keys
- File names

### `answer_context(query, repo, budgetTokens)`

Returns one compact text block for final LLM reasoning.

```text
answer_context(
  query="explain how Kira indexes files and filters unnecessary paths",
  repo="kira",
  budgetTokens=1800
)
```

Use when the agent needs enough context to answer, not a raw hit list.

## Symbol And Graph Tools

### `discover_symbols(partialName, type, k)`

Metadata-only symbol discovery. It returns FQN, type, and path without snippets.

```text
discover_symbols(
  partialName="Retrieval",
  type=null,
  k=10
)
```

Type can be:

- `"CLASS"`
- `"METHOD"`
- `"INTERFACE"`
- `"ENDPOINT"`
- `null`

Use this before `get_symbol` when token cost matters.

### `find_symbol(partialName, type)`

Finds candidate symbols and can include snippets.

```text
find_symbol(
  partialName="RetrievalOrchestrator",
  type="CLASS"
)
```

Use when you need more context than `discover_symbols` gives.

### `get_symbol(fqn)`

Returns one Java symbol with signature, javadoc, body, direct callers, and direct callees. Body text is capped at 8000 characters.

```text
get_symbol(
  fqn="com.acme.airetrieval.retrieve.RetrievalOrchestrator#answerContext(String, SearchFilter, int)"
)
```

Use when you already know the exact FQN.

### `get_callers(fqn, depth)`

Returns caller FQNs/signatures up to a graph depth.

```text
get_callers(
  fqn="com.acme.airetrieval.retrieve.RetrievalOrchestrator#answerContext(String, SearchFilter, int)",
  depth=1
)
```

Start with `depth=1`. Increase only when investigating transitive impact.

### `get_callees(fqn, depth)`

Returns callee FQNs/signatures up to a graph depth.

```text
get_callees(
  fqn="com.acme.airetrieval.retrieve.RetrievalOrchestrator#answerContext(String, SearchFilter, int)",
  depth=1
)
```

### `expand_context(fqns, hops, maxResults)`

Walks the graph from one or more comma-separated seed FQNs and returns related signatures.

```text
expand_context(
  fqns="com.acme.airetrieval.retrieve.RetrievalOrchestrator#answerContext(String, SearchFilter, int)",
  hops=1,
  maxResults=25
)
```

Use `maxResults=0` for the default of 50. Results are capped at 200.

## Architecture And Framework Tools

### `get_kafka_flow(topic)`

Finds Kafka producers and consumers for a topic.

```text
get_kafka_flow(
  topic="order-events"
)
```

Use when code uses Kafka topic annotations, constants, or extracted topic metadata.

### `get_endpoint(method, path)`

Looks up one indexed REST endpoint.

```text
get_endpoint(
  method="POST",
  path="/api/v1/search"
)
```

Method examples:

- `"GET"`
- `"POST"`
- `"PUT"`
- `"DELETE"`
- `"PATCH"`

### `get_bean_graph(name, depth)`

Returns Spring constructor-injection dependency graph data.

```text
get_bean_graph(
  name="RetrievalOrchestrator",
  depth=1
)
```

Use a simple class name or partial FQN.

## Code And Documentation Cross-Lookup

### `get_design_for_symbol(fqn)`

Finds design documents or documentation chunks related to a known code symbol.

```text
get_design_for_symbol(
  fqn="com.acme.airetrieval.retrieve.RetrievalOrchestrator"
)
```

Returns a `SearchResult`.

### `get_code_for_doc(docId)`

Finds code chunks related to one documentation chunk id.

```text
get_code_for_doc(
  docId="kira:docs/MCP_ENDPOINT_USAGE.md:section-available-mcp-tools"
)
```

Use a real `id` returned by `search_knowledge` or `answer_context` source context.

### `check_spec_vs_impl(repo)`

Compares indexed OpenAPI spec endpoints with implemented Java REST endpoints for the same repo.

```text
check_spec_vs_impl(
  repo="kira"
)
```

Returns unimplemented, undocumented, matched, total, and error fields.

## Recommended Calling Patterns

### Exact Code Question

```text
keyword_search(query="RetrievalOrchestrator", repo="kira", domain="CODE", k=5)
discover_symbols(partialName="RetrievalOrchestrator", type="CLASS", k=5)
get_symbol(fqn="<best-fqn>")
```

### Documentation Question

```text
search_knowledge(query="file exclusion guide", repo="kira", branch="main", k=5)
answer_context(query="how do I exclude unnecessary files from Kira indexing", repo="kira", budgetTokens=1600)
```

### Endpoint Question

```text
get_endpoint(method="POST", path="/api/v1/search")
check_spec_vs_impl(repo="kira")
```

### Impact Analysis

```text
get_symbol(fqn="<method-fqn>")
get_callers(fqn="<method-fqn>", depth=1)
get_callees(fqn="<method-fqn>", depth=1)
expand_context(fqns="<method-fqn>", hops=1, maxResults=25)
```

### Cross-Domain Design Question

```text
get_design_for_symbol(fqn="<class-or-method-fqn>")
get_code_for_doc(docId="<doc-chunk-id>")
semantic_search(query="how docs and code describe file acceptance", repo="kira", branch="main", k=8)
```
