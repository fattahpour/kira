# Kira Superpowers Implementation

Implemented at the repository root from the phase plans in `docs/superpowers/plans`.

## Phase 0 - Skeleton

- Spring Boot 3.5 / Java 21 Maven project.
- Configuration properties and application YAML.
- Common chunk/search model.
- Markdown, Tika-style document parsing, JGit diff support, Lucene indexing, REST search endpoint.

## Phase 1 - Semantic

- Embedding abstraction with ONNX Runtime implementation.
- Deterministic local fallback embedding when model files are not staged.
- Lucene `KnnFloatVectorField`.
- Hybrid BM25 + KNN retrieval using Reciprocal Rank Fusion.
- Retrieval orchestrator as the main search entry point.

## Phase 2 - Code Graph + MCP Facade

- JavaParser extraction for class and method chunks.
- Graph events for declarations, calls, REST endpoints, Kafka listener consumers.
- GraphStore interface with JGraphT-backed implementation.
- Tool-facing `McpTools` component exposing search, answer context, symbol, callers, callees, and Kafka flow operations.
- REST graph mirrors.

## Phase 3 - Precision

- ONNX cross-encoder `Reranker`.
- `TokenBudget` and `ContextCompactor`.
- `answer-context` REST endpoint and MCP facade method.
- Basic OpenAPI/AsyncAPI path-method parser.

## Phase 4 - Scale

- `GraphStore` abstraction and configurable graph engine.
- Kuzu adapter placeholder preserving the interface without requiring native Kuzu binaries in the default build.
- NRT Lucene reads through `SearcherManager`.
- Full repository reindex service.
- Micrometer counter service for indexed chunks.

## Runtime Notes

- Runtime stays offline. ONNX models are loaded only from configured local paths.
- When local embedding/reranker models are absent, tests and the service still run with deterministic embeddings and reranker disabled.
- The Kuzu path is intentionally optional; default `kira.graph.engine` is `jgrapht`.
