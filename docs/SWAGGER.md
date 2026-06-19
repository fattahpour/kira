# Swagger And OpenAPI

Kira uses Springdoc OpenAPI to generate API documentation at runtime.

For MCP tools, parameters, return shapes, and call examples, see [`docs/MCP_KIRA_TOOL_REFERENCES.md`](MCP_KIRA_TOOL_REFERENCES.md).

## Start The App

From the repository root:

```bash
mvn spring-boot:run
```

Or:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar
```

## Open Swagger UI

```text
http://localhost:8094/swagger-ui.html
```

Swagger UI lets you inspect and call:

- `POST /api/v1/index`
- `POST /api/v1/index/incremental`
- `POST /api/v1/index/full`
- `POST /api/v1/index/sync/{repoId}`
- `GET /api/v1/index/status`
- `GET /api/v1/index/monitor`
- `POST /api/v1/search`
- `POST /api/v1/answer-context`
- `GET /api/v1/graph/symbol`
- `GET /api/v1/graph/callers`
- `GET /api/v1/graph/callees`
- `GET /api/v1/graph/kafka/{topic}`
- Actuator endpoints exposed by the app.

## Raw OpenAPI Document

JSON:

```text
http://localhost:8094/v3/api-docs
```

YAML, if needed:

```text
http://localhost:8094/v3/api-docs.yaml
```

## Configuration

OpenAPI configuration is in:

```text
src/main/java/com/acme/airetrieval/config/OpenApiConfig.java
```

Swagger path configuration is in:

```text
src/main/resources/application.yml
```

Current properties:

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
    tags-sorter: alpha
```

## Production Toggle

Springdoc logs a warning because Swagger and API docs are enabled by default. Disable them for production with:

```bash
java -jar target/ai-retrieval-0.1.0-SNAPSHOT.jar \
  --springdoc.api-docs.enabled=false \
  --springdoc.swagger-ui.enabled=false
```

Or add profile-specific config:

```yaml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

## IntelliJ HTTP Client

The matching IntelliJ request file is:

```text
http/kira-api.http
```

Use it when you want repeatable calls without opening Swagger UI.

## Index Request Shapes

Single-file indexing:

```json
{
  "repo": "kira",
  "repoDir": "/home/example/projects/kira",
  "path": "README.md",
  "gitSha": "local",
  "branch": "main"
}
```

Full repository indexing:

```json
{
  "repo": "kira",
  "repoDir": "/home/example/projects/kira",
  "gitSha": "local",
  "branch": "main"
}
```

Git incremental indexing:

```json
{
  "repo": "kira",
  "repoDir": "/home/example/projects/kira",
  "fromSha": "abc123",
  "toSha": "def456",
  "branch": "main"
}
```

`branch` is optional. Use it when indexing and searching branch-aware chunks.

Full and single-file indexing apply `.gitignore`, `.git/info/exclude`, and Kira acceptance filters before parsing files. If a file is missing from search results, check `kira.accept.include`, `kira.accept.exclude`, and any per-repo `kira.repos[].accept` overrides. Detailed examples are in:

```text
docs/FILE_EXCLUSION_GUIDE.md
```

## Search Request Shape

```json
{
  "query": "how to start the service",
  "repo": "kira",
  "branch": "main",
  "domain": "KNOWLEDGE",
  "type": null,
  "path": null,
  "k": 5,
  "mode": "hybrid"
}
```

Use `mode: "bm25"` for keyword-only search. Omit `branch` to search all branches for the selected repo.
