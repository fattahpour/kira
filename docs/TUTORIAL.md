# Kira Tutorial

This tutorial walks through starting Kira, indexing files, searching them, using answer context, exploring graph endpoints, and using Swagger UI or IntelliJ HTTP Client.

For a dedicated setup and performance tuning guide, see:

```text
docs/SETUP_AND_PERFORMANCE.md
```

For a document-folder tutorial focused on agents and LLM context, see:

```text
docs/FILES_AND_DOCUMENTS_AGENT_TUTORIAL.md
```

For excluding unnecessary files before indexing, see:

```text
docs/FILE_EXCLUSION_GUIDE.md
```

For every MCP service and how to call it, see:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

## Copy/Paste Example For This Project

Use this section when you want the fastest end-to-end example for a local checkout at:

```text
/home/example/projects/kira
```

### Step 1. Open a terminal at the project root

```bash
cd /home/example/projects/kira
```

### Step 2. Build the app

```bash
mvn clean package
```

Expected result:

```text
target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

### Step 3. Start Kira with isolated local data

Using `/tmp/kira-example-data` keeps this example separate from any existing `~/.kira/data` index.

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --kira.data-dir=/tmp/kira-example-data
```

Leave this terminal running.

### Step 4. Confirm the service is healthy

Open a second terminal and run:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{"status":"UP"}
```

### Step 5. Index one file first

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

Expected result: a JSON response with indexed chunks for `README.md`.

### Step 6. Search the indexed file

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how do I start the service",
    "repo": "kira",
    "branch": "main",
    "k": 5,
    "mode": "hybrid"
  }'
```

Expected result: search hits from `README.md`, including startup commands or service documentation.

### Step 7. Index the whole project

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

Full indexing processes accepted files such as Java sources, Markdown docs, YAML files, JSON files, and text files. It skips ignored paths such as `target/`, `.git/`, compiled classes, and JARs.

### Step 8. Search only code

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "RetrievalOrchestrator hybrid search",
    "repo": "kira",
    "branch": "main",
    "domain": "CODE",
    "k": 10,
    "mode": "hybrid"
  }'
```

Expected result: hits from Java files around retrieval orchestration and hybrid search.

### Step 9. Generate answer context

```bash
curl -X POST http://localhost:8080/api/v1/answer-context \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how does Kira index and search files",
    "repo": "kira",
    "budgetTokens": 1200
  }'
```

Expected result: a compact text context block that can be pasted into another AI tool or prompt.

### Step 10. Inspect graph data

Java files populate the code graph during indexing. Try a known symbol:

```bash
curl 'http://localhost:8080/api/v1/graph/symbol?fqn=com.acme.airetrieval.retrieve.RetrievalOrchestrator%23hybrid(String,%20SearchFilter,%20int)'
```

Then inspect callees:

```bash
curl 'http://localhost:8080/api/v1/graph/callees?fqn=com.acme.airetrieval.retrieve.RetrievalOrchestrator%23hybrid(String,%20SearchFilter,%20int)&depth=1'
```

Expected result: JSON describing the symbol and related method calls if the symbol was extracted.

### Step 11. Reset the example data

Stop the running app with `Ctrl+C`, then remove the isolated data directory:

```bash
rm -rf /tmp/kira-example-data
```

## 1. Prerequisites

Install:

- JDK 21 or newer.
- Maven.
- IntelliJ IDEA if you want to use the `.http` request file.

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
├── http/kira-api.http
└── docs/TUTORIAL.md
```

## 2. Build And Test

From the repository root:

```bash
mvn clean test
```

Build the JAR:

```bash
mvn package
```

The JAR is written to:

```text
target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

## 3. Start Kira

Development mode:

```bash
mvn spring-boot:run
```

Packaged mode:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

Use a different port:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --server.port=9090
```

Use an isolated data directory:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --kira.data-dir=/tmp/kira-data
```

## 4. Confirm It Is Running

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

More Swagger details are in:

```text
docs/SWAGGER.md
```

## 5. Index Content

Kira searches only indexed content. Index one file first:

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

Index that file into a specific branch:

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
- `path`: file path relative to `repoDir`, only for `POST /api/v1/index`.
- `gitSha`: label stored on indexed chunks. Use `local` for local/manual indexing.
- `fromSha` and `toSha`: Git commit SHAs for `/incremental`.
- `branch`: optional branch label stored on chunks and used for branch-filtered search.

Full and single-file indexing respect `.gitignore` and `.git/info/exclude` by default. Disable that only when needed:

```yaml
kira:
  respect-gitignore: false
```

Kira then applies `kira.accept.include` and `kira.accept.exclude`. Use those filters for tracked files that `.gitignore` cannot hide, generated code, dependency folders, archives, local secrets, and test files you do not want in retrieval. After changing filters, restart Kira and run a full reindex.

Minimal exclusion example:

```yaml
kira:
  accept:
    exclude:
      - "**/target/**"
      - "**/build/**"
      - "**/node_modules/**"
      - "**/*.log"
      - "**/.env*"
```

## 6. Configure Branch Auto-Sync

Manual indexing is useful for local testing. For ongoing indexing, configure repos under `kira.repos` and let Kira sync local Git branches on an interval.

Single-branch config:

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

Multi-branch config:

```yaml
kira:
  repos:
    - id: kira
      path: /home/example/projects/kira
      branches:
        mode: MULTI
        tracked:
          - "main"
          - "feature/*"
      auto-sync:
        enabled: true
        interval-seconds: 300
```

Trigger a configured repo manually:

```bash
curl -X POST http://localhost:8080/api/v1/index/sync/kira
```

Check checkpoints and last sync timestamps:

```bash
curl http://localhost:8080/api/v1/index/status
```

## 7. Search

Hybrid search uses BM25 plus vector KNN and fuses results with Reciprocal Rank Fusion:

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

Search only one indexed branch:

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

BM25-only search:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "Spring Boot actuator",
    "repo": "kira",
    "k": 5,
    "mode": "bm25"
  }'
```

Search only code chunks:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "RetrievalOrchestrator",
    "repo": "kira",
    "domain": "CODE",
    "k": 10
  }'
```

Search only docs and knowledge chunks:

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "model files",
    "repo": "kira",
    "domain": "KNOWLEDGE",
    "k": 10
  }'
```

## 8. Generate Answer Context

Answer context retrieves relevant hits and compacts them into a budgeted text block:

```bash
curl -X POST http://localhost:8080/api/v1/answer-context \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "how does hybrid search work",
    "repo": "kira",
    "budgetTokens": 1200
  }'
```

Use this endpoint when another tool or model needs a compact context bundle rather than raw search hits.

## 9. Use Code Graph Endpoints

Graph endpoints are populated when Java files are indexed.

Get one symbol:

```bash
curl 'http://localhost:8080/api/v1/graph/symbol?fqn=com.acme.airetrieval.retrieve.RetrievalOrchestrator%23hybrid(String,%20SearchFilter,%20int)'
```

Get callers:

```bash
curl 'http://localhost:8080/api/v1/graph/callers?fqn=com.acme.airetrieval.retrieve.RetrievalOrchestrator%23hybrid(String,%20SearchFilter,%20int)&depth=1'
```

Get callees:

```bash
curl 'http://localhost:8080/api/v1/graph/callees?fqn=com.acme.airetrieval.retrieve.RetrievalOrchestrator%23hybrid(String,%20SearchFilter,%20int)&depth=1'
```

Get Kafka flow:

```bash
curl http://localhost:8080/api/v1/graph/kafka/orders
```

## 10. Use Swagger UI

Start the app, then open:

```text
http://localhost:8080/swagger-ui.html
```

Useful Swagger workflow:

1. Open `Index Controller`.
2. Run `POST /api/v1/index` or `POST /api/v1/index/full`.
3. Run `GET /api/v1/index/status` if auto-sync is configured.
4. Open `Search Controller`.
5. Run `POST /api/v1/search`.
6. Open `Graph Controller` after indexing Java files.

The raw OpenAPI document is available at:

```text
http://localhost:8080/v3/api-docs
```

## 11. Use IntelliJ HTTP Client

Open:

```text
http/kira-api.http
```

In IntelliJ:

1. Start Kira with `mvn spring-boot:run`.
2. Open `http/kira-api.http`.
3. Click the green run icon next to `Health`.
4. Run `Index one file` or `Full repository reindex`.
5. Run `Hybrid search`.
6. Run `Index status` if you are using configured auto-sync.
7. Adjust variables at the top of the file if your path or port differs.

Important variables:

```http
@host = http://localhost:8080
@repo = kira
@repoDir = /home/example/projects/kira
@gitSha = local
@branch = main
```

## 12. Optional ONNX Models

Kira starts without model files. Without ONNX files, it uses deterministic fallback embeddings so local development works.

For better semantic retrieval, stage:

```text
~/.kira/data/models/bge-small.onnx
~/.kira/data/models/tokenizer.json
```

Optional reranker:

```text
~/.kira/data/models/reranker.onnx
~/.kira/data/models/reranker-tokenizer.json
```

Enable reranking:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --kira.reranker.enabled=true
```

Reindex after changing embedding models.

## 13. Reset Local Data

Delete the local Lucene and graph data:

```bash
rm -rf ~/.kira/data/lucene ~/.kira/data/graph ~/.kira/data/checkpoint.json
```

Then restart Kira and reindex.

## 14. Troubleshooting

Search returns no hits:

- Run indexing first.
- Make sure the search `repo` matches the indexed `repo`.
- If you indexed with `branch`, include the same `branch` in search.
- Try `"mode": "bm25"` to test BM25-only search.

Swagger UI does not load:

- Confirm the app is running.
- Open `http://localhost:8080/v3/api-docs`.
- If `/v3/api-docs` works, refresh `http://localhost:8080/swagger-ui.html`.

Port 8080 is busy:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar --server.port=9090
```

Java warnings appear:

- The project targets Java 21.
- Running on newer JDKs may emit Lucene or JVM native-access warnings.
- Use JDK 21 for the cleanest runtime.
