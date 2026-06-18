# Kira Setup And Performance Tutorial

This guide explains how to set up Kira from a clean checkout, index a repository, verify the API, and tune performance for indexing and search.

For every MCP service and how to call it from an agent, see [`docs/MCP_KIRA_TOOL_REFERENCES.md`](MCP_KIRA_TOOL_REFERENCES.md).

## 1. Requirements

Install:

- JDK 21 or newer.
- Maven 3.9 recommended.
- IntelliJ IDEA if you want to use the HTTP client file.

Check your tools:

```bash
java -version
mvn -version
```

The project is a root-level Maven project:

```text
kira/
├── pom.xml
├── src/
├── docs/
└── http/kira-api.http
```

## 2. Build And Test

From the project root:

```bash
cd /home/example/projects/kira
mvn clean test
```

Build the runnable JAR:

```bash
mvn clean package
```

The packaged application is created here:

```text
target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

## 3. Start Kira

For development:

```bash
mvn spring-boot:run
```

For packaged runtime:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

Use a different port if `8080` is busy:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --server.port=9090
```

Use an isolated data directory for testing:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-data
```

## 4. Verify Startup

Health check:

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{"status":"UP"}
```

Swagger UI:

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8080/v3/api-docs
```

## 5. Index Content

Kira must index files before search returns useful results.

Index the whole Kira repository:

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

Index or reindex one file:

```bash
curl -X POST http://localhost:8080/api/v1/index \
  -H 'Content-Type: application/json' \
  -d '{
    "repo": "kira",
    "repoDir": "/home/example/projects/kira",
    "path": "README.md",
    "gitSha": "local"
  }'
```

Index Git changes between two commits:

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

Fields:

- `repo`: logical repository name used later for filtering.
- `repoDir`: absolute filesystem path to the repository.
- `path`: file path relative to `repoDir`, only used by single-file indexing.
- `gitSha`: label stored on indexed chunks. Use `local` for manual indexing.
- `fromSha` and `toSha`: commit SHAs used by Git incremental indexing.
- `branch`: optional branch label stored on chunks and used for branch-filtered search.

## 6. Configure Branch Auto-Sync

For ongoing indexing, configure repositories under `kira.repos`. Auto-sync checks local Git branch HEADs and runs either a full reindex, an incremental index, or a no-op based on the stored checkpoint.

Single branch:

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
```

Multiple branches:

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

Trigger sync manually and inspect status:

```bash
curl -X POST http://localhost:8080/api/v1/index/sync/kira
curl http://localhost:8080/api/v1/index/status
```

Checkpoint state is written to `kira.checkpoint-file`, defaulting to `${kira.data-dir}/checkpoint.json`.

## 7. Configure File Acceptance

Kira respects `.gitignore` and `.git/info/exclude` during full and single-file indexing, then applies include/exclude filters before source classification. Git incremental indexing uses Git diffs, so ignored untracked files do not enter that path.

For a dedicated guide with common exclusion recipes and reindex commands, see [`docs/FILE_EXCLUSION_GUIDE.md`](FILE_EXCLUSION_GUIDE.md).

Use `.gitignore` for normal local junk. Use Kira `accept.exclude` for tracked files, generated source, vendored dependencies, archives, secrets, tests, or any content that should not be retrieved by an LLM.

Gitignore filtering is enabled by default:

```yaml
kira:
  respect-gitignore: true
```

The global acceptance filter lives under `kira.accept`; per-repo filters can override it under `kira.repos[].accept`.

Example:

```yaml
kira:
  accept:
    include:
      - "**/*.java"
      - "**/*.md"
      - "**/*.yaml"
      - "**/*.json"
    exclude:
      - "**/target/**"
      - "**/.git/**"
      - "**/node_modules/**"
  repos:
    - id: kira
      path: /home/example/projects/kira
      accept:
        include:
          - "src/**/*.java"
          - "docs/**/*.md"
        exclude:
          - "src/test/**"
```

After changing acceptance filters, restart Kira and run a full reindex so old chunks are removed from Lucene:

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

## 8. Search

Hybrid search uses BM25 keyword search plus vector KNN search:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how to start the service",
    "repo": "kira",
    "k": 5,
    "mode": "hybrid"
  }'
```

Branch-filtered search:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how to start the service",
    "repo": "kira",
    "branch": "main",
    "k": 5,
    "mode": "hybrid"
  }'
```

BM25-only search is faster and is useful for exact identifiers:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "FullReindexService",
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
    "query": "GraphQueries callers",
    "repo": "kira",
    "domain": "CODE",
    "k": 10
  }'
```

Filter to documentation and knowledge:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "performance tuning",
    "repo": "kira",
    "domain": "KNOWLEDGE",
    "k": 10
  }'
```

## 9. Use IntelliJ HTTP Client

Open:

```text
http/kira-api.http
```

Workflow:

1. Start Kira.
2. Open `http/kira-api.http` in IntelliJ.
3. Run `Health`.
4. Run `Index one file` or `Full repository reindex`.
5. Run `Hybrid search`.
6. Run `Index status` if you are using configured auto-sync.
7. Adjust the variables at the top if your port or path differs.

Important variables:

```http
@host = http://localhost:8080
@repo = kira
@repoDir = /home/example/projects/kira
@gitSha = local
@branch = main
```

## 10. Use Swagger

Open:

```text
http://localhost:8080/swagger-ui.html
```

Recommended Swagger workflow:

1. Run `GET /actuator/health` outside Swagger or open the health URL.
2. Run `POST /api/v1/index` or `POST /api/v1/index/full`.
3. Run `POST /api/v1/search`.
4. Run `GET /api/v1/index/status` if auto-sync is configured.
5. Run graph endpoints after Java files have been indexed.

## 11. Runtime Data

Default data paths:

```text
~/.kira/data
~/.kira/data/lucene
~/.kira/data/models
~/.kira/data/graph
~/.kira/data/checkpoint.json
```

Clear local runtime data:

```bash
rm -rf ~/.kira/data/lucene ~/.kira/data/graph ~/.kira/data/checkpoint.json
```

After clearing data, restart Kira and reindex.

## 12. Improve Indexing Performance

### Use Full Reindex For First Load

Use `/api/v1/index/full` for the first repository load. Use `/api/v1/index/incremental` after that for Git changes, or configure auto-sync to do this from branch checkpoints.

Incremental indexing is faster because it deletes and replaces only changed file paths instead of walking and reprocessing the whole repository.

### Prefer Auto-Sync For Tracked Repositories

For repositories you query repeatedly, configure `kira.repos` and use auto-sync. It avoids repeated full reindexes and lets Kira skip unchanged branches by comparing stored checkpoints with branch HEADs.

Keep `interval-seconds` high enough for your repository size. Invalid values of `0` or lower are skipped at startup.

### Tune Index Threads

Default:

```yaml
kira:
  executor:
    index-threads: 4
```

Start with CPU cores minus one:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.executor.index-threads=7
```

For small machines, use fewer threads:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.executor.index-threads=2
```

### Tune Full Reindex Batch Size

Default:

```yaml
kira:
  full-reindex:
    batch-size: 200
```

Larger batches reduce reopen overhead during full indexing:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.full-reindex.batch-size=500
```

Use smaller batches if memory pressure appears:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.full-reindex.batch-size=50
```

### Put Data On Fast Storage

Lucene benefits from fast local SSD storage. Avoid slow network-mounted paths for `kira.data-dir` and `kira.index-dir`.

Good local setup:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir="$HOME/.kira/data"
```

Temporary high-speed test setup:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-data
```

### Increase JVM Heap For Large Repositories

For medium repositories:

```bash
java -Xms1g -Xmx4g -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

For large repositories:

```bash
java -Xms2g -Xmx8g -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

If the process swaps, reduce heap or indexing threads. Swapping hurts Lucene and ONNX performance.

## 13. Improve Search Performance

### Use BM25 For Exact Searches

Use BM25 mode for class names, method names, paths, and exact terms:

```json
{
  "query": "IndexService indexIncremental",
  "repo": "kira",
  "k": 5,
  "mode": "bm25"
}
```

Use hybrid mode for natural-language questions:

```json
{
  "query": "how does indexing delete stale chunks",
  "repo": "kira",
  "k": 5,
  "mode": "hybrid"
}
```

### Keep `k` Small

Start with `k: 5` or `k: 10`. Larger values return more hits and increase response time.

Default limits:

```yaml
kira:
  default-search-k: 10
  max-search-results: 50
```

Raise `max-search-results` only when callers truly need larger result sets:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.max-search-results=100
```

### Filter By Repo, Branch, Domain, Type, Or Path

Filtering reduces the amount of content that must be searched and reranked:

```json
{
  "query": "OpenAPI parser",
  "repo": "kira",
  "branch": "main",
  "domain": "CODE",
  "path": "src/main/java",
  "k": 10
}
```

Use:

- `repo` to isolate a repository.
- `branch` to isolate a branch.
- `domain` to search `CODE` or `KNOWLEDGE`.
- `type` to search a specific chunk type.
- `path` to restrict to an exact stored path.

### Keep Reranking Off Until Needed

Reranking is disabled by default:

```yaml
kira:
  reranker:
    enabled: false
```

Enable it only when quality matters more than latency:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.reranker.enabled=true
```

Reranking needs these files:

```text
~/.kira/data/models/reranker.onnx
~/.kira/data/models/reranker-tokenizer.json
```

### Tune Answer Context Size

Large answer contexts take longer to build and transmit. Default:

```yaml
kira:
  token-budget:
    default-budget-tokens: 6000
    chars-per-token: 4
```

For lower latency, request a smaller budget:

```bash
curl -X POST http://localhost:8080/api/v1/answer-context \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how does hybrid search work",
    "repo": "kira",
    "budgetTokens": 1200
  }'
```

## 14. Improve Semantic Quality

Kira can start without ONNX model files. In that mode, it uses deterministic fallback embeddings, which are useful for development but not strong semantic retrieval.

For better semantic search, stage:

```text
~/.kira/data/models/bge-small.onnx
~/.kira/data/models/tokenizer.json
```

Then restart and reindex:

```bash
rm -rf ~/.kira/data/lucene
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

Reindexing is required after changing embedding models because vectors are stored in the Lucene index.

## 15. Measure Performance

Expose metrics:

```text
http://localhost:8080/actuator/metrics
```

Useful metric names:

```text
kira.indexed.chunks
kira.search.requests
kira.search.latency
kira.embed.latency
kira.reranker.latency
kira.tokens.returned.last
```

Example:

```bash
curl http://localhost:8080/actuator/metrics/kira.search.latency
```

Benchmark manually:

```bash
time curl -s -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "retrieval orchestrator",
    "repo": "kira",
    "k": 10,
    "mode": "hybrid"
  }' > /tmp/kira-search.json
```

Compare:

- `mode: "bm25"` versus `mode: "hybrid"`.
- `k: 5` versus `k: 20`.
- reranker disabled versus enabled.
- different `kira.executor.index-threads` values during full reindex.

## 16. Recommended Profiles

### Local Development

```bash
java -Xms512m -Xmx2g -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.reranker.enabled=false \
  --kira.executor.index-threads=2 \
  --kira.full-reindex.batch-size=100
```

### Fast Indexing On A Workstation

```bash
java -Xms2g -Xmx8g -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.executor.index-threads=8 \
  --kira.full-reindex.batch-size=500 \
  --kira.reranker.enabled=false
```

### Better Search Quality

```bash
java -Xms2g -Xmx8g -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.reranker.enabled=true \
  --kira.default-search-k=10 \
  --kira.max-search-results=50
```

Use this profile only after ONNX embedding and reranker files are staged.

## 17. Troubleshooting

Search returns no hits:

- Run indexing first.
- Make sure `repo` in the search request matches the `repo` used during indexing.
- If using branch-aware indexing, include the matching `branch` filter.
- Try `"mode": "bm25"` to separate lexical search from embedding quality.

Search is slow:

- Reduce `k`.
- Add `repo`, `domain`, or `path` filters.
- Disable reranking.
- Use BM25 mode for exact searches.
- Move `kira.data-dir` to local SSD storage.

Indexing is slow:

- Increase `kira.executor.index-threads`.
- Increase `kira.full-reindex.batch-size`.
- Increase JVM heap.
- Use Git incremental indexing or configured auto-sync for changed files.
- Tighten `kira.accept` include/exclude patterns.
- Keep the data directory on local SSD storage.

Memory usage is high:

- Reduce `kira.executor.index-threads`.
- Reduce `kira.full-reindex.batch-size`.
- Lower `-Xmx` if the machine starts swapping.
- Keep reranking disabled unless needed.

Semantic results are weak:

- Stage real ONNX embedding files.
- Reindex after changing models.
- Use hybrid mode for natural-language queries.
- Use BM25 mode for exact symbols.
