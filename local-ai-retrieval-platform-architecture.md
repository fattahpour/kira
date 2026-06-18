# Local AI Knowledge & Code Retrieval Platform — Architecture Design

**Target:** Java 21 · Spring Boot · Ubuntu Linux · CPU-only · offline-capable · minimal infra · no Python (unless unavoidable) · no external DB (if avoidable) · incremental indexing · 100K–millions LOC · thousands of documents.

**Optimized for:** maximum retrieval accuracy · minimum token usage · Java-first ecosystem · low operational cost · long-term maintainability.

For the current implemented MCP services, parameters, return shapes, and call examples, see:

```text
docs/MCP_KIRA_TOOL_REFERENCES.md
```

---

## 0. Executive summary (read this first)

The single most important architectural decision is this: **you do not need a vector database, a graph database server, or Python.** A modern **Apache Lucene** index gives you BM25 keyword search **and** dense-vector (HNSW) search **and** metadata filtering in one embedded library, with no server process. **ONNX Runtime for Java** (or LangChain4j's in-process ONNX models) generates embeddings on CPU with no Python. **JavaParser** builds a precise code graph deterministically — *without* spending a single LLM token. That combination satisfies almost every constraint you listed.

This is **one unified platform for both code and knowledge** — not two systems. Both share a single index and graph, joined by deterministic **code↔knowledge links** (design docs/ADRs/specs ↔ classes/endpoints/topics), so it can answer "why is this built this way?" and "which code implements this decision?" in a single call. See **§3A** for the unified model.

Three assumptions worth challenging up front (expanded in §13):

1. **"Knowledge Graph / Graph RAG"** as commonly marketed (Microsoft GraphRAG, Graphiti) uses an **LLM to extract entities and relationships at index time**, which *burns tokens* — the opposite of your goal. For *code*, you get a far more accurate graph for free from the AST. Use **deterministic AST-derived graphs**, not LLM-extracted ones. Reserve LLM extraction (if ever) for unstructured prose where no parser exists.
2. **"Avoid external databases"** is achievable, but a true graph DB query engine (Cypher, recursive traversals at millions-of-nodes scale) is genuinely useful. The compromise: use an **embedded** graph engine (Kuzu or Neo4j-embedded) — still a single process, single JAR/lib, no separate server to operate.
3. **Graphiti is Python** (and pulls in Neo4j/FalkorDB + an LLM per write). It directly violates three of your constraints. Skip it.

**Recommended core stack:**

```
Apache Tika + commonmark   → ingestion (PDF, Word, Confluence HTML, Markdown)
JavaParser + SymbolSolver  → deterministic code graph (no LLM, no tokens)
ONNX Runtime (Java)        → CPU embeddings, offline, no Python
Apache Lucene 10           → BM25 + dense KNN + filters, ONE embedded index (no DB)
RRF hybrid fusion          → combine lexical + semantic
ONNX cross-encoder rerank  → precision boost (optional, prod)
Embedded graph (Kuzu/Neo4j)→ code-graph traversal (prod; MVP can stay in Lucene+JGraphT)
JGit                       → incremental indexing by git diff
Spring AI 1.1 MCP server   → expose tools to Claude Code / Codex / Gemini CLI / Copilot / OpenCode
Spring Boot 3.5 (or 4.0)   → REST API, packaging, observability
```

---

## 1. Approach-by-approach comparison

Ratings are relative within this problem domain. Scale: **Low / Med / High**. "Token reduction impact" = how much this component helps you send fewer tokens to the downstream LLM. "Effort" = engineering days for a competent Java dev to integrate well (not from scratch).

> Note on diagrams: several items below are **libraries/components**, not standalone architectures. For those, the "diagram" shows their role in the pipeline rather than a system topology.

### 1.1 Apache Lucene — *the keystone*

Role: embedded search engine. BM25 lexical + HNSW dense vector + filtered search in one index. This is what lets you skip a vector DB.

```
            ┌─────────────── Lucene Index (memory-mapped, on disk) ───────────────┐
 query ──▶  │  [inverted index: BM25]   [HNSW graph: KNN float vectors]  [docvals] │ ──▶ hits
            │   text fields                vector field                  filters    │
            └─────────────────────────────────────────────────────────────────────┘
```

| Dimension | Assessment |
|---|---|
| Advantages | One library = lexical + vector + filter + facets; battle-tested; pure JVM; memory-mapped, scales to tens of millions of docs on one box; incremental `updateDocument`; no server to operate |
| Disadvantages | Lower-level API (you build the pipeline); HNSW tuning (M, beamWidth) is on you; no built-in distributed sharding (you'd add it) |
| Accuracy | High (BM25 is a strong baseline; hybrid lifts it further) |
| Query latency | Low (single-digit–low-tens ms for BM25; 5–30 ms KNN at 384-dim over millions) |
| CPU | Low at query time; Med during index merges |
| Storage | Med (inverted index + vectors; ~1.5–2.5 KB/chunk at 384-dim float32, less with int8) |
| Maintenance | Low–Med (you own the schema/analyzers) |
| Token reduction | **High** — precise retrieval + filtering is the foundation of sending less |
| Effort | 3–6 days |

### 1.2 Apache Tika

Role: universal text/metadata extraction (PDF, DOCX, PPTX, HTML/Confluence exports, etc.).

```
PDF/DOCX/HTML ──▶ Tika (AutoDetectParser) ──▶ plain text + metadata ──▶ chunker
```

| Dimension | Assessment |
|---|---|
| Advantages | Handles ~all your doc formats with one API; pure Java; mature; pulls structured metadata |
| Disadvantages | PDF layout/table fidelity is imperfect; large dep tree; OCR (Tesseract) needed for scanned PDFs |
| Accuracy | Med–High (format-dependent; great for DOCX/HTML, fair for messy PDFs) |
| Query latency | N/A (index-time only) |
| CPU | Low–Med (PDF parsing spikes) |
| Storage | N/A |
| Maintenance | Low |
| Token reduction | Indirect (clean text → better chunks → less noise retrieved) |
| Effort | 1–2 days |

### 1.3 JavaParser (+ Symbol Solver)

Role: deterministic AST → classes, interfaces, methods, calls, fields, annotations. **Source of your code graph and code chunk boundaries.**

```
*.java ──▶ JavaParser AST ──▶ SymbolSolver (resolve types/calls) ──▶ nodes+edges + method-level chunks
```

| Dimension | Assessment |
|---|---|
| Advantages | Precise, zero-token code structure; method-level chunking; extracts annotations (@RestController, @KafkaListener, @Repository, @Bean) → endpoints/consumers/beans for free; pure Java |
| Disadvantages | SymbolSolver needs classpath to resolve fully (else partial call resolution); slower on millions of LOC (parallelize); doesn't see runtime/reflection wiring |
| Accuracy | High for static structure; Med for dynamic (reflection, SpEL, proxies) |
| Query latency | N/A (index-time) |
| CPU | Med–High at full reindex (parallelizable per file) |
| Storage | Low (graph is small relative to text) |
| Maintenance | Med (keep up with Java language versions) |
| Token reduction | **High** — return a method + its callers/callees signatures instead of whole files |
| Effort | 5–10 days for a solid extractor |

### 1.4 LangChain4j

Role: optional Java orchestration layer — RAG plumbing, in-process ONNX embeddings, MCP client/server, embedding-store abstractions.

| Dimension | Assessment |
|---|---|
| Advantages | Ships **in-process ONNX embedding models** (all-MiniLM-L6-v2, bge-small) — CPU, offline, no Python; MCP client + stdio server; many store adapters incl. Lucene; idiomatic Java |
| Disadvantages | Opinionated abstractions can fight a custom engine; overlaps with Spring AI (pick a lane); fast-moving API |
| Accuracy | N/A (framework) — depends on chosen models |
| Query latency | Low overhead |
| CPU | N/A |
| Storage | N/A |
| Maintenance | Med (active project, frequent releases) |
| Token reduction | Med (RAG advisors, context filters help if you use them) |
| Effort | 2–4 days |

### 1.5 Spring AI (1.1 GA)

Role: Spring-native orchestration — `ChatClient`, RAG advisors, **first-class MCP server starter**, observability via Micrometer.

| Dimension | Assessment |
|---|---|
| Advantages | Native to your stack; **MCP server starter** (stdio + streamable HTTP) makes the agent-facing surface trivial; ONNX/Transformers embedding support; auto-config + starters; Micrometer metrics |
| Disadvantages | Younger than LangChain4j in some adapters; 2.0 will track Spring Boot 4 (watch for churn); embedding-store choices are fewer than LC4j |
| Accuracy | N/A (framework) |
| Query latency | Low overhead |
| CPU | N/A |
| Storage | N/A |
| Maintenance | Low–Med (Spring release discipline) |
| Token reduction | Med (advisors, structured outputs) |
| Effort | 2–4 days |

**Verdict (1.4 vs 1.5):** In a Spring Boot shop, use **Spring AI for the MCP server + ChatClient**, and keep the *retrieval engine* framework-agnostic (raw Lucene + ONNX). Borrow LangChain4j's in-process ONNX embedding model if you want the fastest path to local embeddings. Don't run both frameworks' RAG stacks.

### 1.6 ONNX Runtime (Java)

Role: run embedding + reranker models on CPU, offline, no Python. Pair with a HuggingFace tokenizer (DJL `huggingface-tokenizers` or LangChain4j).

```
text ──▶ HF tokenizer (Java) ──▶ ORT session (CPU, int8/fp32) ──▶ float[ ] embedding
```

| Dimension | Assessment |
|---|---|
| Advantages | True CPU/offline inference; quantization (int8/dynamic) for 2–4× speedup; no Python; model-portable (any exported HF model) |
| Disadvantages | You manage tokenizer + pooling + normalization yourself; throughput is CPU-bound (batch + thread-pool it) |
| Accuracy | Depends on model (see §1.15) |
| Query latency | Low per query (1–10 ms for MiniLM int8; more for 0.6B models) |
| CPU | Med (the main CPU consumer in the system) |
| Storage | Low (model files 50–600 MB) |
| Maintenance | Med (model/version management) |
| Token reduction | Enables semantic retrieval → **High** indirectly |
| Effort | 3–5 days (tokenizer + pooling + batching done right) |

### 1.7 Vector Search (dense)

Role: semantic similarity over embeddings (here: Lucene HNSW).

| Dimension | Assessment |
|---|---|
| Advantages | Finds meaning, not just keywords; great for docs/NL queries; handles synonyms/paraphrase |
| Disadvantages | Weak on exact identifiers (`OrderServiceImpl`, error codes); embedding quality caps accuracy; recall depends on HNSW params |
| Accuracy | Med–High for prose; Med for code identifiers |
| Query latency | Low (HNSW) |
| CPU | Low at query; Med to build vectors |
| Storage | Med (vectors dominate index size) |
| Maintenance | Low–Med |
| Token reduction | High (relevance ranking) |
| Effort | included in Lucene work |

### 1.8 BM25 (lexical)

Role: classic keyword ranking — excellent for code identifiers, error strings, exact terms.

| Dimension | Assessment |
|---|---|
| Advantages | Superb on exact tokens/symbols; zero model cost; interpretable; instant |
| Disadvantages | No semantics/synonyms; misses paraphrased questions |
| Accuracy | High for exact-term queries; Low for conceptual queries |
| Query latency | Very Low |
| CPU | Very Low |
| Storage | Low |
| Maintenance | Low |
| Token reduction | High (when query has the right keywords) |
| Effort | trivial (Lucene default) |

### 1.9 Hybrid Search (BM25 + vector, fused)

Role: combine 1.7 + 1.8 via Reciprocal Rank Fusion (RRF) or normalized score fusion. **This is your default retrieval mode.**

```
query ─┬─▶ BM25 top-k ───┐
       └─▶ KNN  top-k ───┴─▶ RRF fuse ─▶ (rerank) ─▶ top-n
```

| Dimension | Assessment |
|---|---|
| Advantages | Best of both: exact-symbol recall + semantic recall; robust across query types; biggest single accuracy win for code+docs |
| Disadvantages | Two retrievals + fusion logic; needs tuning (k, weights); slightly more latency |
| Accuracy | **High** (consistently beats either alone) |
| Query latency | Low–Med |
| CPU | Low–Med |
| Storage | same as Lucene |
| Maintenance | Med |
| Token reduction | **High** |
| Effort | 2–3 days on top of Lucene |

### 1.10 Knowledge Graph (deterministic, AST-derived)

Role: typed nodes/edges for code (Class, Method, Bean, Endpoint, Topic) + relationships (CALLS, IMPLEMENTS, DEPENDS_ON, PRODUCES, CONSUMES).

```
(Controller)-[EXPOSES]->(Endpoint)
(Service)-[CALLS]->(Repository)-[QUERIES]->(Table)
(Producer)-[PRODUCES]->(Topic)<-[CONSUMES]-(Listener)
```

| Dimension | Assessment |
|---|---|
| Advantages | Answers structural questions retrieval can't ("who calls X?", "what consumes topic Y?"); precise context expansion; **zero tokens to build** (from AST) |
| Disadvantages | Build + storage of graph; traversal engine needed at scale; only as good as static analysis |
| Accuracy | High (deterministic) |
| Query latency | Low (embedded graph) to Med (deep traversals) |
| CPU | Low at query; Med to build |
| Storage | Low–Med |
| Maintenance | Med |
| Token reduction | **Very High** — return the exact neighborhood, not whole files |
| Effort | 5–10 days (shared with JavaParser work) |

### 1.11 Graph RAG

Role: use the graph to *expand/curate* retrieved context (retrieve seed nodes → traverse N hops → assemble compact, relevant subgraph for the LLM).

| Dimension | Assessment |
|---|---|
| Advantages | Highest-precision context assembly; great for "explain how this flow works end-to-end"; dramatic token savings vs dumping files |
| Disadvantages | Pipeline complexity; **if built the Microsoft/LLM-extraction way, it costs tokens at index time** — avoid that for code |
| Accuracy | High (with a good graph) |
| Query latency | Med |
| CPU | Med |
| Storage | Med |
| Maintenance | Med–High |
| Token reduction | **Very High** |
| Effort | 4–8 days (on top of graph + hybrid) |

### 1.12 Neo4j

Role: graph engine. Can run **embedded** (in-process, no server) or as a server.

| Dimension | Assessment |
|---|---|
| Advantages | Mature Cypher; great traversals; embedded mode = single process; rich tooling/visualization |
| Disadvantages | Embedded Community has licensing/packaging nuances and is less emphasized upstream; heavier dep; another data store to manage |
| Accuracy | N/A (engine) |
| Query latency | Low–Med (excellent for multi-hop) |
| CPU | Low–Med |
| Storage | Med |
| Maintenance | Med (you said "avoid external DB"; embedded softens but doesn't remove this) |
| Token reduction | via Graph RAG: High |
| Effort | 3–6 days |

### 1.13 Graphiti

Role: temporal knowledge-graph framework (Zep).

| Dimension | Assessment |
|---|---|
| Advantages | Nice temporal/episodic memory model; incremental graph updates |
| Disadvantages | **Python**; requires **Neo4j/FalkorDB**; **calls an LLM per write to extract entities** (token cost + latency + network). Conflicts with no-Python, no-external-DB, offline, and minimize-tokens — i.e. four of your constraints |
| Accuracy | Good for prose memory; overkill/misaligned for code |
| Query latency | Med |
| CPU | Med (+ LLM calls) |
| Storage | Med |
| Maintenance | High (Python+DB+LLM moving parts) |
| Token reduction | **Negative at index time** |
| Effort | High and off-stack |

**Verdict: do not use Graphiti here.** AST gives you a better code graph deterministically.

### 1.14 Rerankers (cross-encoder)

Role: re-score top-K candidates with a query-document cross-encoder (ONNX, CPU). Lets you retrieve broadly then keep only the few best.

```
top-50 candidates ──▶ cross-encoder (query,doc) ──▶ scored ──▶ keep top-5
```

| Dimension | Assessment |
|---|---|
| Advantages | Largest precision-per-effort gain after hybrid; enables retrieve-many-keep-few → big token savings; CPU-runnable (small models) |
| Disadvantages | Adds latency (scores K pairs); model choice/quantization matters |
| Accuracy | **High** uplift on top-k ordering |
| Query latency | Med (e.g. 20–80 ms for 50 pairs with a small int8 model) |
| CPU | Med (per-query bump) |
| Storage | Low (model 100–600 MB) |
| Maintenance | Med |
| Token reduction | **Very High** |
| Effort | 2–4 days |

### 1.15 Embedding models (CPU, ONNX, no Python)

| Model | Dim | Size | CPU speed | Accuracy | Notes |
|---|---|---|---|---|---|
| **all-MiniLM-L6-v2** | 384 | ~90 MB | Very fast | Med | Great MVP default; ships in-process with LangChain4j |
| **bge-small-en-v1.5** | 384 | ~130 MB | Fast | Med–High | Strong small general model; good code+doc baseline |
| **EmbeddingGemma-300M** | 768 (Matryoshka→128/256) | ~300 MB | Med | High | Best-in-class <500M; truncatable dims to cut storage/latency |
| **Qwen3-Embedding-0.6B** | up to 1024 (configurable) | ~600 MB | Med–Slow | High | Top accuracy that's still CPU-feasible; instruction-aware |
| **jina-embeddings-v2-base-code** | 768 | ~160 MB | Fast–Med | High (code) | Code-specialized; pairs well with a general model for docs |
| BGE-M3 | 1024 | ~2.2 GB | Slow on CPU | High | Dense+sparse+multivector; heavy for CPU-only |

Recommendation: start **bge-small** (or MiniLM) everywhere → measure → upgrade docs to **EmbeddingGemma-300M** and optionally route *code* chunks to **jina-v2-code**. Use **Matryoshka truncation** (e.g. 256-dim) to cut vector storage and KNN latency with minor accuracy loss. Keep query-time and index-time models identical.

---

## 2. Recommended MVP

Goal: useful in ~2–3 weeks, proves accuracy + token savings, no DB, no Python.

```
                ┌───────────────────────── Spring Boot app (single JAR) ─────────────────────────┐
 git repo ─▶    │  JGit diff ─▶ [Tika | commonmark | JavaParser] ─▶ chunker ─▶ ONNX embed ─▶ Lucene│
 docs dir ─▶    │                                                                                  │
                │  Query ─▶ BM25 ⨁ KNN ─(RRF)─▶ top-n ─▶ REST/MCP ─▶ Claude Code / Codex / Gemini  │
                └──────────────────────────────────────────────────────────────────────────────────┘
                                    Lucene index + graph json on local disk
```

MVP scope:
- Ingest: Java (JavaParser, method-level chunks), Markdown (heading-aware), PDF/DOCX/Confluence-HTML (Tika).
- Embed: bge-small (or MiniLM) via ONNX, int8, batched.
- Index: Lucene with fields `{id, repo, path, type, fqn, symbolKind, gitSha, contentHash, text, vector}`.
- Retrieve: **hybrid (BM25 + KNN, RRF)**; filters by `type`/`repo`/`path`.
- Code graph (lightweight): store edges (`CALLS`, `IMPLEMENTS`, `EXPOSES`, `PRODUCES`, `CONSUMES`) as Lucene docs or a JSON adjacency file; traverse in-memory with JGraphT.
- Incremental: re-index only files whose git blob SHA or content hash changed; `updateDocument` by `path`.
- Surfaces: REST `/search`, `/search/code`, `/index/incremental`; **MCP server** (Spring AI starter) with `search_code`, `search_docs`, `get_symbol`.

Explicitly **out** of MVP: reranker, embedded graph DB, endpoint/Kafka/bean extractors beyond annotations, sharding.

---

## 3. Recommended production architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                              Spring Boot 3.5 service (Ubuntu, JRE 21)                       │
│                                                                                            │
│  ┌── Ingestion ──────────────┐   ┌── Embedding ─────────┐   ┌── Index/Store ─────────────┐ │
│  │ JGit diff watcher         │   │ ONNX Runtime (CPU)   │   │ Lucene 10 (BM25+HNSW+filt) │ │
│  │ Tika / commonmark / JP+SS │──▶│ tokenizer+pool+int8  │──▶│ memory-mapped, segment merge│ │
│  │ structure-aware chunker   │   │ batch + thread pool  │   │ updateDocument (incremental)│ │
│  └───────────────────────────┘   └──────────────────────┘   └────────────────────────────┘ │
│            │                                                          ▲                      │
│            ▼                                                          │                      │
│  ┌── Code Graph ─────────────┐                          ┌── Retrieval Orchestrator ───────┐ │
│  │ Kuzu / Neo4j (EMBEDDED)   │◀────── graph queries ────│ hybrid(BM25⨁KNN→RRF)            │ │
│  │ Class/Method/Bean/Endpoint│                          │ → cross-encoder rerank (ONNX)   │ │
│  │ Topic/Repo/Table + edges  │─────── expand context ──▶│ → graph N-hop expand            │ │
│  └───────────────────────────┘                          │ → context compaction (signatures)│ │
│                                                          └─────────────────────────────────┘ │
│                                                                    │            │            │
│                                          ┌── MCP server (Spring AI)┘            └─ REST API ─┐│
│                                          │ stdio + streamable HTTP │            │ /search ...││
│                                          └─────────────────────────┘            └────────────┘│
│  Observability: Micrometer + Actuator   ·   All state on local SSD   ·   Fully offline        │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
        ▲                                   ▲                                   ▲
   Claude Code                           Codex                            Gemini CLI
   (via MCP)                          (via MCP/REST)                       (via MCP/REST)
```

Production additions over MVP: embedded graph engine (Kuzu or Neo4j-embedded) for real multi-hop traversals; ONNX **reranker**; **context compaction** (return signatures + graph neighborhood, not file bodies); dedicated extractors for **REST endpoints, Kafka producers/consumers, Spring beans, repositories**; Micrometer metrics; optional **int8 vectors** + Matryoshka dims for scale.

---

## 3A. Unified code + knowledge model — *one system, two domains, linked*

**Yes — this is a single platform for both code and knowledge.** It already ingests Java source *and* Markdown/PDF/Word/Confluence/design docs/API specs/Kafka docs. The upgrade here is to stop treating them as two parallel pipelines that happen to share a process, and instead make the **link between them** a first-class capability. That linkage is the entire reason to run one unified system instead of two separate tools: it lets an agent answer questions that neither corpus can answer alone.

### 3A.1 The questions this unlocks

| Question | Needs |
|---|---|
| "What's the **design rationale** behind `PaymentService`?" | code hit → linked ADR/design doc |
| "Which **code implements** ADR-014 (idempotent settlement)?" | doc → linked classes/methods |
| "Does the **implementation match the spec** for `POST /payments`?" | OpenAPI operation ↔ controller method (both sides) |
| "Where is **topic `settlement.completed`** documented *and* produced/consumed?" | Kafka doc ↔ producer/consumer code |
| "Explain the settlement flow end-to-end with **the why and the how**." | blended: design doc + code graph path |

### 3A.2 Unified document model (one corpus, one index)

Every chunk — code or prose — is the same record shape, distinguished by a `domain` field. One Lucene index, filterable and blendable.

```
Chunk {
  id, repo, path, domain: CODE | KNOWLEDGE,
  type: METHOD|CLASS|MD_SECTION|PDF_PAGE|CONFLUENCE|ADR|OPENAPI_OP|ASYNCAPI_CHANNEL|...,
  fqn?            // code: fully-qualified name; docs: null
  title?, section?// docs: heading path; code: null
  symbols[]       // identifiers MENTIONED in this chunk (key to cross-linking)
  annotations[], gitSha, contentHash, lang, vector, text
}
```

### 3A.3 Cross-domain links (the new graph layer)

The code graph (§1.10) gains **knowledge↔code edges**, built mostly *deterministically* — still zero LLM tokens:

```
(ADR/DesignDoc) ─DESCRIBES / DECIDES_ON─▶ (Class | Component)
(Confluence)    ─DOCUMENTS─────────────▶ (Endpoint | Topic | Bean)
(OpenAPI op)    ─SPECIFIES─────────────▶ (Endpoint)        ◀─EXPOSES─ (Controller)
(AsyncAPI chan) ─SPECIFIES─────────────▶ (Topic)  ◀─PRODUCES/CONSUMES─ (Handler)
(MD section)    ─MENTIONS───────────────▶ (Method | Class)   // by symbol match
```

How links are created (in priority order):
1. **Structured parse (exact).** OpenAPI/AsyncAPI → `SPECIFIES` edges to endpoints/topics by `(method,path)` / channel name. Highest precision.
2. **Symbol-mention detection (deterministic).** Scan doc chunks for identifiers that match known FQNs/class names from the AST index → `MENTIONS` edges. Cheap, precise, no model.
3. **Embedding similarity (fuzzy, optional).** For docs with no explicit symbol, link a doc section to its nearest code neighbor *only* above a high threshold, and mark the edge `INFERRED` so retrieval can trust it less. Use sparingly to avoid noise.

ADR/design-doc detection: treat files under `docs/adr/**`, `architecture/**`, or with ADR front-matter as `type=ADR/DESIGN` so they're weighted as authoritative "why" sources.

### 3A.4 Unified retrieval with an intent router

A small classifier (rules + cheap heuristics, no LLM needed) routes each query and sets domain weights:

```
query ─▶ intent router
   ├─ CODE        ("who calls X", "implementation of Y")      → graph + code-weighted hybrid
   ├─ KNOWLEDGE   ("how do I configure", "what is our policy") → knowledge-weighted hybrid
   └─ BLENDED     ("why is X designed this way", "explain flow")→ retrieve BOTH, then link-expand
```

For BLENDED queries the pipeline retrieves from both domains, reranks, then **follows cross-domain edges** so a code hit pulls in its design doc and vice-versa — assembling a "why + how" answer.

### 3A.5 Embedding strategy for two domains (important)

You have two valid options; **RRF makes either work**:

- **Option A — one shared general model** (e.g. EmbeddingGemma-300M) for code *and* docs. Single vector space → scores are directly comparable → blended ranking is trivial. **Recommended default.**
- **Option B — two specialist models** (a code model for code chunks, a doc model for prose). Different vector spaces → you *cannot* compare cosine scores across them. Solution: retrieve **per-domain separately**, then fuse by **Reciprocal Rank Fusion**, which uses *ranks not scores* and is therefore space-agnostic. This is exactly why the §1.9 design already uses RRF — it future-proofs you for dual models.

Start with Option A; move to Option B in Phase 4 only if your golden-set eval shows code retrieval lagging.

### 3A.6 Blended context assembly (token-efficient)

The compactor (§9.6) becomes domain-aware and splits the token budget:

```
BLENDED answer (budget e.g. 6k tokens):
  ~30%  KNOWLEDGE: the relevant design-doc / ADR / spec SECTION (the "why")
  ~50%  CODE:      the hit method body + neighbor signatures (the "how")
  ~20%  GRAPH:     the cross-domain link summary (e.g. "ADR-014 → PaymentService.settle()")
```

This is where unification pays off in *tokens*: instead of the agent fetching a whole design doc *and* several code files separately, one blended call returns the linked slice of each.

### 3A.7 Updated unified architecture diagram

```
                    ┌──────────────── INGEST (domain-tagged) ────────────────┐
  CODE   *.java ───▶│ JavaParser+SymbolSolver → methods/classes + annotations │
  KNOW   *.md    ───│ commonmark → heading sections                           │
         *.pdf/doc ─│ Tika → text+metadata                                    │──┐
         confluence │ Jsoup/Tika → normalized sections                        │  │
         api specs ─│ OpenAPI/AsyncAPI → operations/channels                  │  │
                    └─────────────────────────────────────────────────────────┘  │
                                          │ chunks {domain, symbols[], ...}        │
              ┌───────────────────────────┼─────────────────────────────────────┐ │
              ▼                            ▼                                       ▼ │
     ┌── ONNX embeddings ──┐   ┌── Lucene (ONE index) ──┐   ┌── Graph + CROSS-LINKS ─┐
     │ shared model (A) or │──▶│ BM25 + KNN + filter by │   │ Class/Method/Bean/...   │
     │ code+doc models (B) │   │ domain=CODE|KNOWLEDGE  │   │ + ADR/Doc/Spec nodes    │
     └─────────────────────┘   └────────────────────────┘   │ DESCRIBES/SPECIFIES/    │
                                          ▲                  │ DOCUMENTS/MENTIONS      │
                                          │                  └──────────┬──────────────┘
                    ┌── intent router ────┴──────────────┐              │
   query ──────────▶│ CODE | KNOWLEDGE | BLENDED         │──────────────┘
                    │  hybrid → rerank → cross-domain expand → compact (why+how)        │
                    └───────────────┬───────────────────────────────┬──────────────────┘
                          MCP server (Spring AI)              REST API
                       Claude Code · Codex · Gemini CLI
```

**Net change to the architecture:** add the `domain` field, the knowledge↔code edge types, the symbol-mention linker, and the intent router. Everything else (Lucene, ONNX, hybrid+RRF, MCP) is unchanged — it was already built to carry both.

---

## 4. Recommended indexing pipeline

```
1. Source discovery     : JGit lists tracked files at HEAD; compute set changed since last-indexed commit
2. Classify             : .java | .md | .pdf/.docx/.html(confluence) | .yaml/.json(api specs)
3. Parse/extract        :
      .java   → JavaParser AST → {class, method, field, annotations}; SymbolSolver → resolved calls
      .md     → commonmark → split by H1/H2/H3 sections
      docs    → Tika → text + metadata → split by heading/paragraph window
      specs   → OpenAPI/AsyncAPI parser → per-operation/per-channel chunks
4. Chunk                : code = per-method (+ class-summary chunk); docs = heading windows ~256–512 tokens, 10–15% overlap
5. Enrich metadata      : repo, path, fqn, symbolKind, annotations, gitSha(blob), contentHash(xxhash), lang, lastModified
6. Embed                : ONNX batch (e.g. 64), normalize; (optional) route code→code-model, docs→doc-model
7. Graph emit           : nodes (Class/Method/Bean/Endpoint/Topic/Repo/Table) + edges (CALLS/IMPLEMENTS/DEPENDS_ON/EXPOSES/PRODUCES/CONSUMES/QUERIES)
8. Upsert               : Lucene updateDocument(Term("id")); graph upsert by stable node id
9. Delete handling      : files removed in diff → deleteDocuments + detach graph nodes
10. Commit + checkpoint  : Lucene commit; persist last-indexed commit SHA + per-file contentHash map
```

Incremental triggers (post `git pull`/`merge`/`checkout`): a hook or watcher calls `/index/incremental` with `fromSha=<last>`, `toSha=HEAD`. Only changed blobs are reparsed/reembedded. Per-chunk `contentHash` short-circuits re-embedding when a file changed but a given method body did not. This is what keeps reindex cost proportional to the diff, not the repo.

Throughput notes: parse + embed are the costs. Parallelize parsing per file (`parallelStream`), embed in batches on a bounded thread pool sized to physical cores. For initial full index of millions of LOC, expect hours of CPU but it's one-time; incrementals are seconds–minutes.

---

## 5. Recommended retrieval pipeline

```
query ─▶ classify(intent)
        ├─ structural? ("who calls X", "consumers of topic T") ─▶ GRAPH query ─▶ compact subgraph
        └─ lexical/semantic ─▶ HYBRID:
                BM25(top 50)  ⨁  KNN(top 50)  ─RRF─▶ candidates(top 30)
                ─▶ cross-encoder rerank ─▶ top 8
                ─▶ GRAPH expand (1–2 hops on top hits: add callers/callees/endpoint owners)
                ─▶ CONTEXT COMPACTION:
                      code  → method signature + javadoc + body (only the hit) + neighbor signatures
                      docs  → matched section only (not whole document)
                ─▶ DEDUPE + BUDGET (cap total tokens, e.g. ≤ 4–6k) ─▶ return
```

Token-minimization levers (in priority order):
1. **Rerank then truncate** — retrieve 50, return 5–8. Biggest lever.
2. **Compaction** — return the relevant *method/section*, plus *signatures* of graph neighbors, not full files.
3. **Graph-targeted expansion** — add exactly the callers/callees/owners the question needs.
4. **Token budget enforcement** — hard cap; drop lowest-scored until under budget.
5. **Filters first** — narrow by repo/type/path before scoring.

Intent routing example: "How does payment settlement flow from API to Kafka?" → seed hybrid search on "settlement" → take top endpoints/services → graph traverse `EXPOSES`→`CALLS`*→`PRODUCES`→`Topic` → return the chain of signatures + the 2–3 key method bodies. That answers an end-to-end question in a few hundred tokens instead of dumping a dozen files.

---

## 6. Recommended MCP server design

Use the **Spring AI MCP server starter** (stdio for local CLIs like Claude Code; streamable HTTP for networked agents). Expose **narrow, token-efficient tools** — each returns the minimum useful slice. Agents compose them.

Tools:

| Tool | Input | Returns (compact) |
|---|---|---|
| `search_code` | query, filters(repo/path), k | ranked {fqn, path, signature, snippet, score} |
| `search_knowledge` | query, filters, k | ranked {title, path, section, snippet, score} (docs/ADRs/specs) |
| `semantic_search` | query, k | mixed code+doc hits (hybrid+rerank), domain-blended |
| `answer_context` | query, budgetTokens | **blended** why+how: linked doc section + code body + cross-link summary |
| `get_design_for_symbol` | fqn | ADRs/design docs linked to a class/method (the "why") |
| `get_code_for_doc` | docId/adrId | classes/methods that implement a decision/doc |
| `check_spec_vs_impl` | method+path | OpenAPI operation ↔ controller method, side by side |
| `get_symbol` | fqn | signature + javadoc + body + {callers[], callees[]} as signatures |
| `get_callers` / `get_callees` | fqn, depth | edge list of signatures (no bodies) |
| `get_endpoint` | httpMethod+path or handlerFqn | controller method + DTOs + downstream calls |
| `get_kafka_flow` | topic | {producers[], consumers[]} with handler signatures |
| `get_bean_graph` | beanName/type | bean + injected deps (DEPENDS_ON) |
| `get_repository` | entity/repoFqn | repo methods + bound tables/queries |
| `expand_context` | seedFqns[], hops | curated subgraph (signatures), budget-capped |
| `index_status` | — | last commit, counts, staleness |

Design rules:
- **Default to signatures, not bodies.** Bodies only on explicit `get_symbol` or when the hit *is* the answer.
- **Always return stable ids** (`fqn`, `path#anchor`) so the agent can drill down cheaply.
- **Enforce a per-call token budget**; include a `truncated: true` flag + `next` cursor instead of dumping.
- **Deterministic ordering** so agent caching works.

Spring AI exposes methods annotated `@Tool` on a Spring bean; the starter handles the MCP transport/handshake. Claude Code, Codex, and Gemini CLI all speak MCP, so one server serves all three; "future agents" get it free.

---

## 7. Recommended REST API design

REST mirrors the MCP tools for agents/integrations that prefer HTTP, plus admin/index ops.

```
POST /api/v1/index/full              {repo, path}                 → jobId
POST /api/v1/index/incremental       {repo, fromSha, toSha}       → {changed, embedded, deleted}
GET  /api/v1/index/status            → {lastSha, docCount, nodeCount, lastRunAt}

POST /api/v1/search                  {query, mode=hybrid|bm25|vector, filters, k, rerank}
POST /api/v1/search/code             {query, filters, k}
POST /api/v1/search/semantic         {query, k, budgetTokens}

GET  /api/v1/graph/symbol/{fqn}      → {signature, javadoc, callers[], callees[]}
GET  /api/v1/graph/callers/{fqn}?depth=2
GET  /api/v1/graph/callees/{fqn}?depth=2
GET  /api/v1/graph/endpoint?method=POST&path=/payments
GET  /api/v1/graph/kafka/topic/{topic}
GET  /api/v1/graph/bean/{name}
POST /api/v1/context/expand          {seedFqns[], hops, budgetTokens}

GET  /actuator/health   GET /actuator/metrics   GET /actuator/prometheus
```

Conventions: JSON; cursor pagination; every result carries `{id, path, score}`; `budgetTokens` honored server-side; `4xx` on bad filters; idempotent incremental indexing keyed by `toSha`.

---

## 8. Recommended Spring Boot project structure

```
ai-retrieval/
├── pom.xml                      (or build.gradle)
├── src/main/java/com/acme/airetrieval/
│   ├── AiRetrievalApplication.java
│   ├── config/                  ApplicationProps, ExecutorConfig, LuceneConfig, OnnxConfig
│   ├── ingest/
│   │   ├── GitChangeDetector.java          (JGit diff)
│   │   ├── SourceClassifier.java
│   │   ├── parser/JavaSourceParser.java    (JavaParser + SymbolSolver)
│   │   ├── parser/MarkdownParser.java       (commonmark)
│   │   ├── parser/DocumentParser.java       (Tika)
│   │   ├── parser/ApiSpecParser.java        (OpenAPI/AsyncAPI)
│   │   └── chunk/Chunker.java
│   ├── embed/
│   │   ├── EmbeddingModel.java              (interface)
│   │   ├── OnnxEmbeddingModel.java          (ORT + tokenizer + pooling)
│   │   └── Reranker.java                    (ONNX cross-encoder)
│   ├── index/
│   │   ├── LuceneIndexer.java               (upsert/delete/commit)
│   │   ├── LuceneSearcher.java              (BM25 + KNN)
│   │   └── HybridSearch.java                (RRF fusion)
│   ├── graph/
│   │   ├── CodeGraphStore.java              (Kuzu/Neo4j-embedded or JGraphT)
│   │   ├── GraphExtractor.java              (nodes/edges from AST)
│   │   └── GraphQueries.java                (callers/callees/endpoints/kafka/beans)
│   ├── retrieve/
│   │   ├── RetrievalOrchestrator.java       (intent → pipeline)
│   │   ├── ContextCompactor.java
│   │   └── TokenBudget.java
│   ├── mcp/McpTools.java                    (@Tool methods)
│   └── api/                                 (REST controllers + DTOs)
├── src/main/resources/
│   ├── application.yml
│   └── models/                              (onnx + tokenizer.json; or external path)
└── src/test/java/...                        (golden-set retrieval eval)
```

Layering rule: `ingest → embed → index/graph → retrieve → (mcp | api)`. The **engine packages stay framework-free**; only `mcp/` and `api/` touch Spring AI/Web. That keeps the core portable and testable.

---

## 9. Sample Java code snippets

> Illustrative; adapt versions/APIs to your pinned dependencies. (Lucene 10.x, JavaParser 3.26+, ONNX Runtime 1.18+, JGit 6/7, Spring AI 1.1, Spring Boot 3.5.)

### 9.1 ONNX CPU embeddings (mean-pool + L2-normalize)

```java
public final class OnnxEmbeddingModel implements EmbeddingModel, AutoCloseable {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer; // ai.djl.huggingface.tokenizers

    public OnnxEmbeddingModel(Path model, Path tokenizerJson) throws OrtException {
        var opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(model.toString(), opts);
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerJson);
    }

    @Override
    public float[] embed(String text) throws OrtException {
        var enc = tokenizer.encode(text);
        long[] ids   = enc.getIds();
        long[] mask  = enc.getAttentionMask();
        long[] shape = {1, ids.length};
        try (var in = OnnxTensor.createTensor(env, LongBuffer.wrap(ids),  shape);
             var am = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
             var r  = session.run(Map.of("input_ids", in, "attention_mask", am))) {

            float[][][] last = (float[][][]) r.get(0).getValue(); // [1][seq][hidden]
            return l2(meanPool(last[0], mask));
        }
    }

    private static float[] meanPool(float[][] tok, long[] mask) {
        int h = tok[0].length; float[] s = new float[h]; long n = 0;
        for (int t = 0; t < tok.length; t++) if (mask[t] == 1) { n++;
            for (int j = 0; j < h; j++) s[j] += tok[t][j]; }
        for (int j = 0; j < h; j++) s[j] /= Math.max(1, n);
        return s;
    }
    private static float[] l2(float[] v) {
        double n = 0; for (float x : v) n += x * x; n = Math.sqrt(n) + 1e-12;
        for (int i = 0; i < v.length; i++) v[i] /= (float) n; return v;
    }
    @Override public void close() throws OrtException { session.close(); }
}
```

### 9.2 Lucene hybrid search (BM25 ⨁ KNN, fused with RRF)

```java
public List<Hit> hybrid(String query, float[] qVec, int k, Query filter) throws IOException {
    // Lexical
    Query bm25 = new QueryParser("text", analyzer).parse(QueryParserBase.escape(query));
    Query bm25f = filter == null ? bm25
        : new BooleanQuery.Builder().add(bm25, MUST).add(filter, FILTER).build();
    TopDocs lex = searcher.search(bm25f, 50);

    // Semantic (HNSW)
    Query knn = new KnnFloatVectorQuery("vector", qVec, 50, filter);
    TopDocs sem = searcher.search(knn, 50);

    // Reciprocal Rank Fusion
    Map<Integer, Double> fused = new HashMap<>();
    rrf(fused, lex, 60); rrf(fused, sem, 60);

    return fused.entrySet().stream()
        .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
        .limit(k)
        .map(e -> toHit(e.getKey(), e.getValue()))
        .toList();
}
private void rrf(Map<Integer, Double> acc, TopDocs td, int k0) {
    int rank = 1;
    for (ScoreDoc sd : td.scoreDocs)
        acc.merge(sd.doc, 1.0 / (k0 + rank++), Double::sum);
}
```

### 9.3 JavaParser: extract methods + call edges

```java
public void extract(CompilationUnit cu, GraphSink sink) {
    cu.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
        String typeFqn = type.getFullyQualifiedName().orElse(type.getNameAsString());
        sink.node(typeFqn, type.isInterface() ? "Interface" : "Class");

        type.getAnnotations().forEach(a -> {
            switch (a.getNameAsString()) {
                case "RestController", "Controller" -> sink.tag(typeFqn, "REST_CONTROLLER");
                case "Repository"                    -> sink.tag(typeFqn, "REPOSITORY");
                case "Component", "Service"          -> sink.tag(typeFqn, "BEAN");
            }
        });

        type.getMethods().forEach(m -> {
            String mFqn = typeFqn + "#" + m.getSignature().asString();
            sink.node(mFqn, "Method");
            sink.edge(typeFqn, "DECLARES", mFqn);

            m.getAnnotations().forEach(a -> {
                if (a.getNameAsString().endsWith("Mapping")) sink.tag(mFqn, "ENDPOINT");
                if (a.getNameAsString().equals("KafkaListener")) sink.tag(mFqn, "KAFKA_CONSUMER");
            });

            m.findAll(MethodCallExpr.class).forEach(call -> {
                try { // requires SymbolSolver configured for full resolution
                    var resolved = call.resolve();
                    sink.edge(mFqn, "CALLS", resolved.getQualifiedSignature());
                } catch (Exception unresolved) { /* record as unresolved edge */ }
            });
        });
    });
}
```

### 9.4 Incremental indexing via JGit diff

```java
public List<Change> changedSince(Repository repo, String fromSha, String toSha) throws Exception {
    try (var git = new Git(repo); var reader = repo.newObjectReader()) {
        var oldTree = new CanonicalTreeParser(); oldTree.reset(reader, repo.resolve(fromSha + "^{tree}"));
        var newTree = new CanonicalTreeParser(); newTree.reset(reader, repo.resolve(toSha   + "^{tree}"));
        return git.diff().setOldTree(oldTree).setNewTree(newTree).call().stream()
            .map(d -> new Change(d.getChangeType(),
                                 d.getChangeType() == DELETE ? d.getOldPath() : d.getNewPath()))
            .toList();
    }
}
// Then: ADD/MODIFY → reparse+reembed+updateDocument(Term("path", p));  DELETE → deleteDocuments(Term("path", p))
```

### 9.5 Spring AI MCP tools

```java
@Component
public class McpTools {
    private final RetrievalOrchestrator retrieval;
    private final GraphQueries graph;

    public McpTools(RetrievalOrchestrator r, GraphQueries g) { this.retrieval = r; this.graph = g; }

    @Tool(description = "Hybrid search over code and docs. Returns compact ranked hits.")
    public List<Hit> semantic_search(@ToolParam String query,
                                     @ToolParam(required = false) Integer k) {
        return retrieval.hybridRerank(query, k == null ? 8 : k);
    }

    @Tool(description = "Get a symbol's signature, javadoc, body, and immediate callers/callees (signatures only).")
    public SymbolView get_symbol(@ToolParam String fqn) {
        return graph.symbolView(fqn);
    }

    @Tool(description = "List producers and consumers for a Kafka topic with handler signatures.")
    public KafkaFlow get_kafka_flow(@ToolParam String topic) {
        return graph.kafkaFlow(topic);
    }
}
```

```yaml
# application.yml — expose the MCP server (stdio for local CLIs, HTTP for networked agents)
spring:
  ai:
    mcp:
      server:
        name: ai-retrieval
        version: 1.0.0
        stdio: true
        # streamable HTTP transport also available for networked agents
```

### 9.6 Context compaction + token budget

```java
public String compact(List<Hit> hits, int budgetTokens) {
    var sb = new StringBuilder(); int used = 0;
    for (Hit h : hits) {
        String block = h.isCode()
            ? h.signature() + "\n" + h.javadoc() + "\n" + h.body()        // the hit: full
            : "## " + h.title() + " › " + h.section() + "\n" + h.snippet(); // doc: section only
        int cost = TokenBudget.estimate(block);
        if (used + cost > budgetTokens) break;
        sb.append(block).append("\n\n"); used += cost;
        for (String neighbor : h.neighborSignatures()) {                   // neighbors: signatures only
            int nc = TokenBudget.estimate(neighbor);
            if (used + nc > budgetTokens) break;
            sb.append(neighbor).append('\n'); used += nc;
        }
    }
    return sb.toString();
}
```

---

## 10. Deployment recommendations

- **Packaging:** single Spring Boot fat JAR; run on **JRE 21**. Optional slim Docker image (`eclipse-temurin:21-jre`) — but a bare JAR + systemd is the lowest-overhead path and fits "minimal infra."
- **Process:** `systemd` unit, restart-on-failure, `WorkingDirectory` = data dir; or Docker with a mounted volume.
- **Storage layout:** local SSD. `data/lucene` (memory-mapped index — give the OS page cache room), `data/graph` (Kuzu/Neo4j files), `models/` (onnx + tokenizer), `data/checkpoint.json` (last SHA + content hashes).
- **Memory:** heap modest (4–8 GB typical); leave plenty of **off-heap/OS cache** for `MMapDirectory`. Vectors are read via the OS cache, not the heap.
- **CPU/threads:** size the embedding executor to **physical cores**; cap concurrent reindex jobs to avoid starving query latency. ONNX `intraOpNumThreads` tuned per box.
- **Offline:** pre-stage all model files locally; no network calls at runtime. The whole system runs air-gapped.
- **Concurrency model:** one writer (indexer) + many readers (Lucene `SearcherManager` for near-real-time reopen after commits).
- **Backup:** snapshot the data dir (Lucene supports commit snapshots) on a schedule; index is rebuildable from git as the source of truth.
- **Observability:** Actuator + Micrometer → Prometheus; track query latency p50/p95, reindex duration, embedding throughput, cache hit rate, tokens-returned-per-query (your north-star metric).
- **Scaling path (if one box isn't enough):** shard the Lucene index by repo/module across instances + a thin fan-out/merge layer; keep each shard embedded. Avoid a distributed DB until you've proven you need it.

---

## 11. Phased implementation roadmap

| Phase | Duration | Deliverable | Exit criterion |
|---|---|---|---|
| **0 — Skeleton** | ~3 days | Spring Boot app, config, data dir, JGit diff, Tika+commonmark ingest, **Lucene BM25 only**, `/search` | Keyword search returns correct files on a real repo |
| **1 — Semantic** | ~1 wk | ONNX embeddings (bge-small/MiniLM), Lucene **KNN**, **hybrid RRF**, incremental upsert by hash | Hybrid beats BM25-only on a golden query set |
| **2 — Code graph + MCP** | ~1–2 wks | JavaParser+SymbolSolver graph (lightweight store), `get_symbol/callers/callees`, **Spring AI MCP server** (stdio) wired to Claude Code | Agent answers "who calls X / consumers of topic T" |
| **3 — Precision + token cuts** | ~1 wk | ONNX **reranker**, **context compaction**, token budget, endpoint/Kafka/bean/repository extractors, REST polish | Measurable token-per-answer drop at equal/*better* accuracy |
| **4 — Scale + harden** | ~1–2 wks | Embedded graph engine (Kuzu/Neo4j), int8/Matryoshka vectors, `SearcherManager` NRT, Micrometer dashboards, full-repo reindex tuning, optional sharding | Millions-LOC repo indexes; p95 query latency within target |

Total to a strong production system: roughly **5–7 weeks** for one focused engineer; the MVP (Phases 0–2) is useful in **2–3 weeks**.

Build a **golden evaluation set early** (Phase 1): ~50–100 real questions with known-good answers/files. Every change is judged against it. Without this, "accuracy" is vibes and "token reduction" is unverifiable.

---

## 12. How each goal is met (traceability)

| Your goal | Mechanism |
|---|---|
| Search code better than text search | Hybrid (BM25 for identifiers ⨁ vectors for concepts) + rerank + method-level chunks |
| Semantic doc search | ONNX embeddings + Lucene KNN over heading-aware chunks |
| Code graph (classes…repositories) | JavaParser + SymbolSolver → typed nodes/edges; annotation-driven endpoint/Kafka/bean/repo tagging |
| Reduce LLM tokens | Retrieve-many-rerank-keep-few + compaction (signatures + relevant section only) + graph-targeted expansion + hard budget |
| Serve Claude Code/Codex/Gemini/future | One MCP server (Spring AI) + parallel REST; all are MCP clients |
| Incremental after pull/merge/checkout | JGit diff → reparse/reembed only changed blobs; per-chunk hash short-circuit |
| Offline | ONNX local models + embedded Lucene/graph; zero runtime network |
| No external DB / no Python / Java-first | Lucene + embedded graph + ONNX-in-Java + JavaParser + Spring; Python avoided entirely |

---

## 13. Challenging your assumptions & better alternatives

1. **"Knowledge graph / Graph RAG" ≠ LLM entity extraction.** The popular GraphRAG/Graphiti pattern spends LLM tokens to *build* the graph from prose. For code that's wasteful and *less* accurate than an AST. **Use deterministic AST graphs.** Keep LLM-extraction only as an optional add-on for pure-prose corpora with no parser — and even then, do it once, cache it, and treat it as best-effort.

2. **"Avoid external databases" — mostly right, but nuance.** Lucene removes the *vector* DB cleanly. For the *graph*, you can stay embedded (Kuzu = embedded, columnar, Cypher-like, excellent for this; Neo4j-embedded if you want full Cypher/tooling). Both are single-process libraries, not servers — so you honor the spirit of the constraint while getting a real traversal engine. Going fully DB-less (graph as JSON/JGraphT) is fine up to ~low hundreds of thousands of edges; beyond that, an embedded engine pays off.

3. **Don't route everything through one framework.** Spring AI and LangChain4j overlap. Use **Spring AI for the MCP server + ChatClient + observability**; keep the **retrieval engine raw** (Lucene + ONNX) so it's portable, testable, and not hostage to framework churn (Spring AI 2.0 will track Spring Boot 4; LangChain4j iterates fast). Borrow LangChain4j's **in-process ONNX embedding model** if you want the fastest start.

4. **Spring Boot version:** **3.5.x is the conservative production baseline** today. Spring Boot 4.0 (Nov 2025, Spring Framework 7) + Spring AI 1.1 is viable and has the best MCP story, but 4.0 drops Jackson 2 and removes JUnit 4 — adopt it deliberately, not by accident. Java 21 works on both.

5. **Reranking is underrated.** If you implement only one "advanced" thing after hybrid, make it a **CPU cross-encoder reranker**. It's the cheapest large gain in both accuracy *and* token reduction (retrieve 50, send 5).

6. **Code-specific embeddings help — selectively.** A code-specialized model (jina-v2-code, BGE-Code, Nomic Embed Code) on *code* chunks plus a general/doc model on *docs* beats one general model for both. But it doubles model management; add it in Phase 4 only if your eval shows code retrieval lagging.

7. **Matryoshka + int8 vectors** (EmbeddingGemma/Qwen3 support truncation) cut storage and KNN latency materially at million-chunk scale with small accuracy loss — worth it for your "millions of LOC" target.

8. **OpenAPI/AsyncAPI are structured — parse, don't just embed.** Your "API specifications" and "Kafka documentation" are often machine-readable (OpenAPI/AsyncAPI). Parse them into graph nodes (endpoints, channels, schemas) so structural queries work, *and* embed the descriptions for semantic search. Treating them as plain text throws away precision.

9. **Confluence exports vary wildly.** HTML exports parse well via Tika/Jsoup; Confluence storage-format XML is messier. Normalize to clean HTML/Markdown at ingest and split on headings — otherwise chunk quality (and thus retrieval) suffers.

10. **Make tokens-returned-per-answer a tracked metric.** You optimize what you measure. Log it per query alongside accuracy on the golden set; it turns "minimize tokens" from an aspiration into a dashboard.
