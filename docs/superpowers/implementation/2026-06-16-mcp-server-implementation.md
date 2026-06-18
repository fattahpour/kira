# MCP Server Implementation Report

Source plan: `docs/superpowers/plans/2026-06-16-mcp-server.md`

## Implemented

- Registered `McpTools` through a Spring AI `ToolCallbackProvider` bean in `McpConfig`.
- Switched MCP support to `spring-ai-starter-mcp-server-webmvc` so stdio and HTTP/SSE transports are available.
- Configured the MCP server as `kira` version `0.1.0` with sync stdio support and SSE message endpoint `/mcp/message`.
- Added `index_status`, backed by `NrtLuceneSearcher.numDocs()` through `RetrievalOrchestrator.indexDocCount()`.
- Added `expand_context`, backed by `GraphQueries.expandContext(List<String>, int)`.
- Added return DTOs for MCP tools: `IndexStatus` and `ExpandedContext`.
- Added root `.mcp.json` with `kira-stdio` and `kira-http` connection definitions for Claude Code.
- Added unit and Spring registration tests for MCP tool behavior and tool exposure.
- Disabled the Spring Boot banner and routed console logs to stderr so stdio MCP stdout stays reserved for JSON-RPC messages.

## Exposed MCP Tools

- `search_code`
- `search_knowledge`
- `semantic_search`
- `answer_context`
- `get_symbol`
- `get_callers`
- `get_callees`
- `get_kafka_flow`
- `expand_context`
- `index_status`

## Verification

Run from the project root:

```bash
mvn test -q
mvn clean package -q -DskipTests
```

Smoke-test HTTP/SSE after packaging:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=target/mcp-smoke-data \
  --kira.reranker.enabled=false \
  --spring.ai.mcp.server.stdio=false \
  --server.port=18080
```

Then check:

```bash
curl -s http://localhost:18080/actuator/health
curl -N -H "Accept: text/event-stream" http://localhost:18080/sse
```

Smoke-test stdio tools/list:

```bash
(
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
) | java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
    --kira.data-dir=target/mcp-stdio-data \
    --spring.ai.mcp.server.stdio=true \
    --kira.reranker.enabled=false \
    --server.port=0
```

The `tools/list` response must include all 10 tools listed above.

## Completed Verification On 2026-06-16

- `mvn test -q` passed.
- `mvn clean package -q -DskipTests` passed.
- Built JAR: `target/ai-retrieval-0.1.0-SNAPSHOT.jar`, 215 MB.
- HTTP/SSE smoke test passed with isolated data directory `target/mcp-smoke-data`:
  - `/actuator/health` returned `{"status":"UP"}`.
  - `/sse` returned an MCP `endpoint` event pointing to `/mcp/message?...`.
- Stdio MCP smoke test passed with isolated data directory `target/mcp-stdio-data`:
  - `initialize` returned protocol version `2024-11-05`.
  - `tools/list` returned all 10 MCP tools.

The first HTTP smoke attempt used the default `~/.kira/data` path and failed because an older Java smoke-test process still held the Lucene `write.lock`. That stale process was stopped, and final verification used isolated data directories to avoid touching existing local indexes.
