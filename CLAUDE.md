# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Kira — Local AI Knowledge & Code Retrieval Platform

Single Spring Boot 3.5 / Java 21 service for hybrid code + knowledge retrieval, exposed as an MCP server to Claude Code / Codex / Gemini CLI. No Python. No external DB. Fully offline-capable.

## Hard constraints

- **No Python** — all inference via ONNX Runtime for Java
- **No external database** — Lucene replaces a vector DB; graph engine is embedded (Kuzu or JGraphT/Neo4j-embedded)
- **CPU-only** — all embedding/reranking models run in-process via ORT
- **Offline** — zero network calls at runtime; models pre-staged locally
- **Incremental** — reindex only git-diff'd blobs, not the full repo

## Core stack

```
Apache Tika + commonmark   → ingestion (PDF, DOCX, Confluence HTML, Markdown)
OpenAPI/AsyncAPI parsers   → API spec ingestion (structured, not plain text)
JavaParser + SymbolSolver  → deterministic code graph, zero LLM tokens
ONNX Runtime (Java)        → CPU embeddings + cross-encoder reranker
Apache Lucene 10           → BM25 + HNSW dense KNN + filters, ONE embedded index
Reciprocal Rank Fusion     → hybrid BM25⊕KNN fusion
JGit                       → incremental indexing by git diff
Spring AI 1.1 MCP server   → stdio + streamable HTTP, exposes @Tool methods
Spring Boot 3.5            → packaging, observability (Micrometer + Actuator)
```

## Planned project structure

```
ai-retrieval/
├── src/main/java/com/acme/airetrieval/
│   ├── config/          ApplicationProps, LuceneConfig, OnnxConfig, ExecutorConfig
│   ├── ingest/          GitChangeDetector, SourceClassifier, parser/, chunk/
│   ├── embed/           EmbeddingModel (interface), OnnxEmbeddingModel, Reranker
│   ├── index/           LuceneIndexer, LuceneSearcher, HybridSearch (RRF)
│   ├── graph/           CodeGraphStore, GraphExtractor, GraphQueries
│   ├── retrieve/        RetrievalOrchestrator, ContextCompactor, TokenBudget
│   ├── mcp/             McpTools (@Tool methods)
│   └── api/             REST controllers + DTOs
└── src/main/resources/
    ├── application.yml
    └── models/          ONNX model files + tokenizer.json
```

Layering rule: `ingest → embed → index/graph → retrieve → (mcp | api)`. The engine packages (`embed`, `index`, `graph`, `retrieve`) must stay framework-free — only `mcp/` and `api/` touch Spring AI / Spring Web.

## Architecture decisions (do not revisit without cause)

**Why not a vector DB?** Lucene 10 provides BM25 + HNSW + metadata filters in one embedded library. No server, no extra process, same accuracy for this scale.

**Why not GraphRAG / Graphiti?** Microsoft GraphRAG and Graphiti call an LLM at *index time* to extract entities — burns tokens and is less accurate than an AST for code. Use JavaParser for deterministic code graphs. LLM extraction reserved only for pure-prose corpora with no parser.

**Hybrid = BM25 ⊕ KNN fused with RRF** (not score fusion). Reciprocal Rank Fusion uses ranks not scores, so it works even when two different embedding models (code model + doc model) are used in Phase 4.

**Token minimization priority order:**
1. Rerank then truncate (retrieve 50, return 5–8) — biggest lever
2. Context compaction — return method signatures + relevant section, not full files
3. Graph-targeted expansion — callers/callees as signatures only
4. Hard token budget enforcement per call
5. Filters first — narrow by `repo`/`type`/`path` before scoring

**Embedding strategy:** Start with one shared general model (bge-small or EmbeddingGemma-300M) for code and docs (Option A). Only split into code model + doc model (Option B) in Phase 4 if eval shows code retrieval lagging. RRF handles dual-model fusion.

## Unified document model

Every chunk (code or prose) shares the same schema. `domain` field distinguishes them:

```
Chunk { id, repo, path, domain: CODE|KNOWLEDGE, type, fqn?, title?, section?,
        symbols[], annotations[], gitSha, contentHash, lang, vector, text }
```

Cross-domain graph edges link code to documentation: `DESCRIBES`, `DOCUMENTS`, `SPECIFIES`, `MENTIONS`. These are built deterministically (structured parse → symbol-mention detection → optional embedding similarity fallback). ADR/design files under `docs/adr/**` or `architecture/**` are tagged `type=ADR`.

## MCP tools

Complete tool catalog with call examples:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

Current Kira MCP services:

- `index_status()` — return indexed document count and server version
- `search_code(query, repo, branch, k)` — search code chunks
- `search_knowledge(query, repo, branch, k)` — search documentation and knowledge chunks
- `semantic_search(query, repo, branch, k)` — search code and knowledge together
- `keyword_search(query, repo, domain, k)` — BM25-only exact-name search
- `answer_context(query, repo, budgetTokens)` — compact context for final reasoning
- `find_symbol(partialName, type)` — symbol discovery with snippets
- `discover_symbols(partialName, type, k)` — metadata-only symbol discovery
- `get_symbol(fqn)` — signature, javadoc, body, callers, callees
- `get_callers(fqn, depth)` — caller signatures
- `get_callees(fqn, depth)` — callee signatures
- `expand_context(fqns, hops, maxResults)` — related signatures around seed FQNs
- `get_kafka_flow(topic)` — Kafka producers and consumers
- `get_endpoint(method, path)` — REST endpoint lookup
- `get_bean_graph(name, depth)` — Spring constructor-injection graph
- `get_design_for_symbol(fqn)` — docs related to code
- `get_code_for_doc(docId)` — code related to docs
- `check_spec_vs_impl(repo)` — OpenAPI vs Java endpoint comparison
- `refresh_index(repo, repoDir)` — blocking full reindex from MCP

Default to signatures, not bodies. Bodies only on explicit `get_symbol` or when the hit is the answer. Every result includes a stable id (`fqn` or `path#anchor`) for drill-down.

## Implementation phases

| Phase | Deliverable |
|---|---|
| 0 — Skeleton | Spring Boot app, JGit diff, Tika+commonmark ingest, Lucene BM25, `/search` |
| 1 — Semantic | ONNX embeddings, Lucene KNN, hybrid RRF, incremental upsert by content hash |
| 2 — Code graph + MCP | JavaParser+SymbolSolver graph, `get_symbol/callers/callees`, Spring AI MCP (stdio) |
| 3 — Precision | ONNX reranker, context compaction, token budget, endpoint/Kafka/bean extractors |
| 4 — Scale | Embedded graph engine (Kuzu), int8/Matryoshka vectors, NRT `SearcherManager`, Micrometer |

Build a **golden evaluation set** (~50–100 real queries with known-good answers) in Phase 1. Every change must be judged against it. Track `tokens-returned-per-answer` as a dashboard metric.

## Embedding model guidance

| Model | Dim | Notes |
|---|---|---|
| bge-small-en-v1.5 | 384 | Default MVP; fast, good baseline |
| EmbeddingGemma-300M | 768 (truncatable) | Best <500M model; Matryoshka dims to cut storage |
| jina-embeddings-v2-base-code | 768 | Code-specialized; add in Phase 4 if code retrieval lags |

Keep query-time and index-time models identical. Use `int8` quantization for 2–4× CPU speedup.

## Deployment

- Single fat JAR, `JRE 21`, systemd unit or Docker with mounted volume
- Storage: `data/lucene/` (memory-mapped, leave OS page cache room), `data/graph/`, `models/`, `data/checkpoint.json`
- Heap 4–8 GB; leave off-heap headroom for `MMapDirectory`
- One writer thread (indexer) + many readers (`SearcherManager` NRT)
- Size ONNX `intraOpNumThreads` to physical cores; cap concurrent reindex jobs
