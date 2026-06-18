# MCP Endpoint Usage

This guide explains how to use Kira through MCP instead of calling the REST API directly.

Use this when an agent or MCP-capable client should search indexed repos, documents, and code through Kira tools.

For the complete tool catalog with every MCP service and copy/paste call examples, see:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

## 1. Endpoint Summary

Kira supports two MCP connection styles:

| Mode | Client Connects To | Best For |
| --- | --- | --- |
| Stdio | A `java -jar ...` command | One agent starts its own Kira process |
| HTTP/SSE | `http://localhost:8080/sse` | One running Kira service shared by multiple clients |

Important HTTP paths:

| Path | Purpose |
| --- | --- |
| `/sse` | MCP client SSE connection URL |
| `/mcp/message` | Internal SSE message endpoint configured by `spring.ai.mcp.server.sse-message-endpoint` |

Most MCP clients should use:

```text
http://localhost:8080/sse
```

Do not use `/mcp/message` as the client URL unless your MCP client specifically asks for the message endpoint.

## 2. Build Kira

From the Kira project root:

```bash
cd /home/example/projects/kira
mvn clean package
```

The JAR is created at:

```text
target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

## 3. Index Data Before Using MCP

MCP tools search the existing Kira index. Index at least one repo or document folder first.

Example document folder:

```bash
curl -X POST http://localhost:8080/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "office-docs",
    "repoDir": "/home/example/knowledge-base",
    "gitSha": "local-docs",
    "branch": "main"
  }'
```

Example code repo:

```bash
curl -X POST http://localhost:8080/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "backend-api",
    "repoDir": "/home/example/projects/backend-api",
    "gitSha": "local",
    "branch": "main"
  }'
```

One Kira instance can index multiple repos. Use the `repo` argument in MCP tool calls to choose which indexed source to search.

Indexing respects `.gitignore`, `.git/info/exclude`, global `kira.accept` filters, and per-repo `kira.repos[].accept` filters. Configure exclusions before indexing large repos so MCP tools do not waste context on build output, generated code, dependencies, archives, or local secrets. See:

```text
docs/FILE_EXCLUSION_GUIDE.md
```

## 4. Use Stdio MCP

Use stdio when the MCP client starts Kira as a child process.

Example MCP client config:

```json
{
  "mcpServers": {
    "kira": {
      "command": "java",
      "args": [
        "-jar",
        "/home/example/projects/kira/target/ai-retrieval-0.1.0-SNAPSHOT.jar",
        "--spring.ai.mcp.server.stdio=true",
        "--spring.ai.mcp.server.type=SYNC",
        "--kira.data-dir=/tmp/kira-doc-agent-data",
        "--server.port=0"
      ],
      "env": {}
    }
  }
}
```

Use the same `--kira.data-dir` used when indexing. If the MCP process uses a different data directory, it will not see the index you created.

## 5. Use HTTP/SSE MCP

Use HTTP/SSE when you want to start Kira once and share it between multiple MCP clients.

Start Kira:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-doc-agent-data \
  --spring.ai.mcp.server.stdio=false \
  --spring.ai.mcp.server.type=ASYNC \
  --server.port=8080
```

MCP client config:

```json
{
  "mcpServers": {
    "kira": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

Health check for the running service:

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{"status":"UP"}
```

## 6. Available MCP Tools

Kira exposes these MCP tools:

| Tool | Purpose |
| --- | --- |
| `search_code(query, repo, branch, k)` | Search indexed code chunks with hybrid reranking |
| `search_knowledge(query, repo, branch, k)` | Search indexed documents and knowledge files with hybrid reranking |
| `semantic_search(query, repo, branch, k)` | Search both code and knowledge with hybrid reranking |
| `keyword_search(query, repo, domain, k)` | Fast BM25-only search for exact names and identifiers |
| `answer_context(query, repo, budgetTokens)` | Return compact context for an LLM answer |
| `get_symbol(fqn)` | Get one indexed Java symbol |
| `find_symbol(partialName, type)` | Find candidate symbols by partial class, method, endpoint, or FQN fragment |
| `discover_symbols(partialName, type, k)` | Find symbols with fqn/type/path only and no snippet text |
| `get_callers(fqn, depth)` | Get callers of a Java symbol |
| `get_callees(fqn, depth)` | Get callees of a Java symbol |
| `get_kafka_flow(topic)` | Get producers and consumers for a Kafka topic |
| `expand_context(fqns, hops, maxResults)` | Expand graph context from seed FQNs with a result cap |
| `refresh_index(repo, repoDir)` | Trigger a blocking full reindex from an MCP client |
| `get_endpoint(method, path)` | Look up a REST endpoint handler by HTTP method and path |
| `get_bean_graph(name, depth)` | Traverse Spring constructor-injection dependencies |
| `get_design_for_symbol(fqn)` | Find design or documentation chunks related to a code symbol |
| `get_code_for_doc(docId)` | Find code chunks related to a documentation chunk |
| `check_spec_vs_impl(repo)` | Compare indexed OpenAPI endpoints with implemented REST endpoints in the same repo |
| `index_status()` | Return indexed document count and server version |

For parameter details, return shapes, and recommended calling patterns for all tools, see [MCP Tool References](MCP_KIRA_TOOL_REFERENCES.md).

The search-style tools return an object shaped like:

```json
{
  "hits": [],
  "error": null
}
```

Check `error` before assuming an empty `hits` list means no results.

## 7. Example MCP Calls

Check that the MCP server can see the index:

```text
index_status()
```

Search documents:

```text
search_knowledge(
  query="what is the vacation policy",
  repo="office-docs",
  branch="main",
  k=5
)
```

Search code:

```text
search_code(
  query="where is authentication handled",
  repo="backend-api",
  branch="main",
  k=5
)
```

Search across code and documents:

```text
semantic_search(
  query="how do users reset passwords",
  repo=null,
  branch="main",
  k=8
)
```

Fast exact-name or identifier search without embedding:

```text
keyword_search(
  query="PaymentService",
  repo="backend-api",
  domain="CODE",
  k=5
)
```

Generate compact context for an LLM:

```text
answer_context(
  query="summarize the refund policy and cite source files",
  repo="office-docs",
  budgetTokens=1800
)
```

Look up a Java symbol:

```text
get_symbol(
  fqn="com.example.auth.AuthService#login(LoginRequest)"
)
```

Find a symbol when you only know part of the name:

```text
find_symbol(
  partialName="AuthService",
  type="CLASS"
)
```

`find_symbol` returns:

```json
{
  "symbols": [],
  "error": null
}
```

Inspect `error` before treating an empty `symbols` list as a clean miss.

For lower-token symbol discovery, use metadata-only results:

```text
discover_symbols(
  partialName="Auth",
  type=null,
  k=10
)
```

Find callers:

```text
get_callers(
  fqn="com.example.auth.AuthService#login(LoginRequest)",
  depth=1
)
```

Find callees:

```text
get_callees(
  fqn="com.example.auth.AuthService#login(LoginRequest)",
  depth=1
)
```

Expand graph context around one or more symbols:

```text
expand_context(
  fqns="com.example.auth.AuthService#login(LoginRequest)",
  hops=1,
  maxResults=25
)
```

Find Kafka producers and consumers:

```text
get_kafka_flow(
  topic="order-events"
)
```

Trigger a blocking full reindex from MCP:

```text
refresh_index(
  repo="backend-api",
  repoDir="/home/example/projects/backend-api"
)
```

Look up an indexed REST endpoint:

```text
get_endpoint(
  method="POST",
  path="/api/v1/login"
)
```

Explore Spring bean dependencies:

```text
get_bean_graph(
  name="AuthService",
  depth=1
)
```

Find docs related to a code symbol:

```text
get_design_for_symbol(
  fqn="com.example.auth.AuthService"
)
```

Find code related to a documentation chunk:

```text
get_code_for_doc(
  docId="backend-api:docs/auth.md:section-login-flow"
)
```

Compare OpenAPI specs with implemented Java endpoints for one repo:

```text
check_spec_vs_impl(
  repo="backend-api"
)
```

`check_spec_vs_impl` returns:

```json
{
  "repo": "backend-api",
  "unimplemented": [],
  "undocumented": [],
  "matched": [],
  "total": 0,
  "error": null
}
```

Java REST endpoint nodes are repo-tagged, so this comparison is scoped to the `repo` you pass instead of mixing endpoints from every indexed repo.

## 8. Agent Usage Pattern

For document questions:

1. Call `index_status()`.
2. Call `search_knowledge()` with a specific `repo`.
3. Check the returned `error` field before reading `hits`.
4. Call `answer_context()` when the agent needs compact final-answer context.
5. Tell the LLM to answer only from retrieved context and cite file paths.

For code questions:

1. Call `search_code()` with a specific `repo`.
2. Check the returned `error` field before reading `hits`.
3. If you do not know the exact FQN, call `find_symbol()`.
4. If a hit includes an FQN, call `get_symbol()`.
5. Use `get_callers()` or `get_callees()` only when call flow matters.
6. Use `keyword_search()` for exact names and identifiers.
7. Use `semantic_search()` only if the answer may require both docs and code.

For API endpoint questions:

1. Call `get_endpoint(method, path)` when you know the route.
2. Call `check_spec_vs_impl(repo)` when comparing OpenAPI specs with Java handlers.
3. Check the returned `error` field before trusting an empty comparison report.

## 9. Troubleshooting

`index_status()` returns `0`:

- Index files first.
- Confirm the MCP process uses the same `kira.data-dir` as the indexing process.
- Confirm you did not start a separate Kira instance with an empty data directory.
- Confirm your include/exclude filters did not exclude every file in the repo.

HTTP MCP client cannot connect:

- Confirm Kira is running with `--spring.ai.mcp.server.stdio=false`.
- Confirm the client URL is `http://localhost:8080/sse`.
- Confirm the service is healthy with `curl http://localhost:8080/actuator/health`.

Search returns no hits:

- Pass the correct `repo` value.
- Pass the same `branch` value used when indexing.
- Try `repo=null` or `branch=null` only when debugging filters.
- Check `.gitignore` and `kira.accept.exclude` if a specific file should have been indexed but is missing.

Too much context comes back:

- Lower `k` for search tools.
- Use `discover_symbols()` instead of search tools when you only need symbol names.
- Pass `maxResults` to `expand_context()`.
- Use `answer_context()` with a clear `budgetTokens` value.
- Prefer `search_code()` or `search_knowledge()` over `semantic_search()` when the domain is known.
- Exclude generated, vendored, and build-output files, then reindex.
