# Kira

Kira is a local AI retrieval service for code and knowledge files. It indexes Markdown, documents, Java source, and basic API specs into an embedded Lucene index, builds an in-memory code graph from Java ASTs, and exposes retrieval through REST endpoints plus an MCP-style tool facade.

The Maven project lives at the repository root.

## What It Includes

- Spring Boot 3.5.11 service packaged as one fat JAR.
- Lucene 10 BM25 keyword search and KNN vector search.
- Hybrid BM25 + KNN retrieval with Reciprocal Rank Fusion.
- Offline ONNX embedding and reranker classes.
- Deterministic fallback embeddings when local ONNX models are not staged.
- Markdown parsing with CommonMark.
- Document parsing path using Apache Tika.
- Java source parsing with JavaParser.
- Code graph queries for symbols, callers, callees, endpoints, and Kafka topics.
- MCP tools for hybrid search, BM25-only keyword search, metadata-only symbol discovery, endpoint lookup, bean graphs, spec-vs-implementation checks, index refresh, and cross-domain code/doc lookup.
- NRT Lucene reads via `SearcherManager`.
- Single-file, Git incremental, and full-repo indexing endpoints.
- Optional branch-aware indexing and branch filters.
- Configured repo auto-sync with per-branch checkpoints.
- Configurable global and per-repo file acceptance filters.
- `.gitignore` and `.git/info/exclude` are respected during full and single-file indexing.
- Actuator health, info, and metrics endpoints.

## Requirements

- JDK 21 or newer.
- Maven 3.9 recommended. Maven 3.8 also works in the current workspace.
- No external database.
- No runtime network access required.

Check tools:

```bash
java -version
mvn -version
```

## Project Layout

```text
kira/
├── pom.xml
├── src/main/java/com/acme/airetrieval/
│   ├── api/          REST controllers
│   ├── config/       Spring configuration
│   ├── embed/        embeddings and reranker
│   ├── graph/        graph store and queries
│   ├── index/        Lucene indexing/search
│   ├── ingest/       parsers and index services
│   ├── mcp/          tool facade
│   ├── observe/      metrics
│   └── retrieve/     orchestration and context compaction
├── src/main/resources/application.yml
└── docs/superpowers/
    ├── plans/
    └── implementation/
```

## Configuration

Default configuration is in `src/main/resources/application.yml`.

Important defaults:

```yaml
kira:
  data-dir: ${user.home}/.kira/data
  index-dir: ${kira.data-dir}/lucene
  models-dir: ${kira.data-dir}/models
  default-search-k: 10
  max-search-results: 50
  candidate-k: 50
  spec-max-ops: 200
  embedding:
    model-path: ${kira.models-dir}/bge-small.onnx
    tokenizer-path: ${kira.models-dir}/tokenizer.json
    dim: 384
  reranker:
    model-path: ${kira.models-dir}/reranker.onnx
    tokenizer-path: ${kira.models-dir}/reranker-tokenizer.json
    enabled: false
  graph:
    engine: jgrapht
  accept:
    include:
      - "**/*.java"
      - "**/*.md"
      - "**/*.markdown"
      - "**/*.yml"
      - "**/*.yaml"
      - "**/*.json"
      - "**/*.pdf"
      - "**/*.docx"
      - "**/*.html"
      - "**/*.txt"
    exclude:
      - "**/target/**"
      - "target/**"
      - "**/.git/**"
      - ".git/**"
      - "**/.idea/**"
      - "**/*.class"
      - "**/*.jar"
      - "**/*.war"
      - "**/node_modules/**"
      - "node_modules/**"
      - "**/.DS_Store"
  repos: []
  respect-gitignore: true
```

You can override settings at startup:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --server.port=8080 \
  --kira.data-dir="$HOME/.kira/data" \
  --kira.reranker.enabled=false
```

## Model Files

The service starts without model files. If the configured ONNX embedding model is missing, Kira uses deterministic local fallback embeddings so development and tests still work.

For real semantic search quality, place model files here:

```text
~/.kira/data/models/bge-small.onnx
~/.kira/data/models/tokenizer.json
```

Optional reranker files:

```text
~/.kira/data/models/reranker.onnx
~/.kira/data/models/reranker-tokenizer.json
```

Then enable the reranker:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.reranker.enabled=true
```

## Build

From the repository root:

```bash
mvn clean package
```

The packaged JAR is created at:

```text
target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

## Test

```bash
mvn test
```

Current suite covers:

- Markdown parsing.
- Java source parsing.
- Code graph storage and graph queries.
- Token budget and context compaction.
- Lucene NRT search.
- Hybrid BM25 + KNN search.
- Spring application context startup.

## Start The Service

From the repository root:

```bash
mvn spring-boot:run
```

Or run the packaged JAR:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

Default URL:

```text
http://localhost:8080
```

Health check:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

## Tutorial And API Clients

Step-by-step tutorial:

```text
docs/TUTORIAL.md
```

Setup and performance tutorial:

```text
docs/SETUP_AND_PERFORMANCE.md
```

Files and documents tutorial for agents and LLMs:

```text
docs/FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md
```

Documentation index:

```text
docs/INDEX.md
```

Agent client configuration guides for Codex, Claude Code, Gemini CLI, GitHub Copilot, and OpenCode:

```text
docs/agent-clients/README.md
```

MCP endpoint usage guide:

```text
docs/MCP_ENDPOINT_USAGE.md
```

Complete MCP tool catalog with every service and call example:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

Swagger/OpenAPI guide:

```text
docs/SWAGGER.md
```

IntelliJ HTTP Client requests:

```text
http/kira-api.http
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

## Index Files

Index one file:

```bash
curl -X POST http://localhost:8080/api/v1/index \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "path": "CLAUDE.md",
    "gitSha": "local"
  }'
```

Index one file for a specific branch:

```bash
curl -X POST http://localhost:8080/api/v1/index \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "path": "README.md",
    "gitSha": "local",
    "branch": "main"
  }'
```

Index the whole repository:

```bash
curl -X POST http://localhost:8080/api/v1/index/full \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "gitSha": "local",
    "branch": "main"
  }'
```

Run a Git incremental index between two commits:

```bash
curl -X POST http://localhost:8080/api/v1/index/incremental \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "fromSha": "abc123",
    "toSha": "def456",
    "branch": "main"
  }'
```

Notes:

- `repoDir` must be an absolute path.
- `path` is relative to `repoDir` and is used by `POST /api/v1/index`.
- `fromSha` and `toSha` are used by `POST /api/v1/index/incremental`.
- `branch` is optional. Omit it for branch-unaware/manual indexing.
- Supported source types include `.java`, `.md`, `.markdown`, `.pdf`, `.docx`, `.html`, `.txt`, `.yml`, `.yaml`, and `.json`.
- Full and single-file indexing respect `.gitignore` and `.git/info/exclude` by default.
- Global and per-repo accept filters decide which remaining files are eligible before source classification.
- The current implementation reads files as text during indexing, so binary document indexing should be improved before relying on PDFs/DOCX in production.
- For detailed file exclusion examples, see [File Exclusion Guide](docs/FILE_EXCLUSION_GUIDE.md).

## Branch Auto-Sync

Kira can sync configured repositories automatically. Each configured repo can track one branch or multiple local branch refs by glob pattern.

Single-branch example:

```yaml
kira:
  repos:
    - id: kira
      path: /home/example/projects/kira
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
        exclude:
          - "src/test/**"
```

Multi-branch example:

```yaml
kira:
  repos:
    - id: kira
      path: /home/example/projects/kira
      branches:
        mode: MULTI
        tracked:
          - "main"
          - "release/*"
          - "feature/*"
      auto-sync:
        enabled: true
        interval-seconds: 300
```

Manual sync and status:

```bash
curl -X POST http://localhost:8080/api/v1/index/sync/kira
curl http://localhost:8080/api/v1/index/status
```

Auto-sync stores checkpoints in `kira.checkpoint-file`, defaulting to `${kira.data-dir}/checkpoint.json`.

## Search

Hybrid search, the default:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "hybrid retrieval lucene",
    "repo": "kira",
    "branch": "main",
    "k": 5,
    "mode": "hybrid"
  }'
```

Omit `branch` to search all branch-unaware and branch-aware chunks for the selected repo.

BM25-only search:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "JavaParser code graph",
    "repo": "kira",
    "k": 5,
    "mode": "bm25"
  }'
```

Filter to code:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "PaymentService settle",
    "repo": "kira",
    "domain": "CODE",
    "k": 10
  }'
```

Filter to knowledge/docs:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how to start the service",
    "repo": "kira",
    "domain": "KNOWLEDGE",
    "k": 10
  }'
```

## Answer Context

`answer-context` retrieves, optionally reranks, and compacts results into a token-budgeted context string.

```bash
curl -X POST http://localhost:8080/api/v1/answer-context \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how does hybrid search work",
    "repo": "kira",
    "budgetTokens": 1200
  }'
```

## Graph Queries

Get a symbol:

```bash
curl 'http://localhost:8080/api/v1/graph/symbol?fqn=com.acme.PaymentService%23settle(String)'
```

Get callers:

```bash
curl 'http://localhost:8080/api/v1/graph/callers?fqn=com.acme.PaymentService%23settle(String)&depth=1'
```

Get callees:

```bash
curl 'http://localhost:8080/api/v1/graph/callees?fqn=com.acme.PaymentService%23settle(String)&depth=1'
```

Get Kafka flow:

```bash
curl 'http://localhost:8080/api/v1/graph/kafka/orders'
```

Graph data is populated when Java files are indexed.

## Useful Development Commands

Compile only:

```bash
mvn compile
```

Run tests:

```bash
mvn test
```

Build JAR:

```bash
mvn package
```

Start on a different port:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --server.port=9090
```

Use a temporary data directory:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-data
```

Clear local index data:

```bash
rm -rf ~/.kira/data/lucene ~/.kira/data/graph ~/.kira/data/checkpoint.json
```

## Operational Notes

- Runtime index data is stored under `~/.kira/data` by default.
- Lucene index files are under `~/.kira/data/lucene`.
- Model files are expected under `~/.kira/data/models`.
- Default graph engine is in-memory JGraphT.
- Kuzu is represented behind the graph engine abstraction but native Kuzu binaries are not required for the default build.
- Reranking is disabled by default.
- The service is offline-capable at runtime.

## Troubleshooting

If the service starts but search returns no hits:

1. Index files first with `/api/v1/index`, `/api/v1/index/full`, or configured auto-sync.
2. Confirm `repo` in the search request matches the `repo` used during indexing.
3. If using branch-aware indexing, confirm `branch` matches the indexed branch.
4. Try BM25-only search with `"mode": "bm25"`.

If startup fails because port 8080 is busy:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --server.port=9090
```

If Java version warnings appear:

- The project targets Java 21.
- Running on newer JDKs may show Lucene or JVM native-access warnings.
- Use JDK 21 for the cleanest runtime.

If semantic quality is weak:

- Stage real ONNX embedding files under `~/.kira/data/models`.
- Reindex after changing embedding models.

## Implementation Notes

Implementation records:

```text
docs/superpowers/implementation/2026-06-15-superpowers-implementation.md
docs/superpowers/implementation/2026-06-16-branch-modes-autosync-implementation.md
docs/superpowers/implementation/2026-06-17-branch-autosync-fixes-implementation.md
```
