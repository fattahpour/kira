# Kira Resource Sizing Guide

CPU, RAM, and disk requirements by indexed file count.

For the MCP services that will query the resulting index, see [`docs/MCP_KIRA_TOOL_REFERENCES.md`](MCP_KIRA_TOOL_REFERENCES.md).

---

## How to use this document

1. Count files after ignoring generated and dependency folders.
2. Find your row in the sizing table.
3. Apply the JVM flags and `application.yml` knobs from that tier.

For exclusion setup before sizing, see:

```text
docs/FILE_EXCLUSION_GUIDE.md
```

All figures assume **bge-small-en-v1.5** (384-dim) with ONNX Runtime CPU-only. Larger embedding models increase RAM and indexing time proportionally.

---

## Resource model

Kira has three resource consumers with distinct profiles:

| Consumer | CPU | RAM | Disk |
|---|---|---|---|
| **ONNX embedding** (bge-small) | High during indexing, idle during search | 200–400 MB fixed overhead | 22 MB model file |
| **Lucene index** (BM25 + HNSW) | Medium during search (BM25 scoring, KNN graph walk) | OS page cache × index size | ~2.2 KB per chunk |
| **JGraphT code graph** | Low | ~150 bytes per node/edge, fully in JVM heap | Serialized on disk (optional) |
| **JVM heap** (parser, chunker, ingest) | Spiky during full reindex | 1–8 GB depending on repo size | — |

---

## Unit costs per file

These are averages. Actual values depend on file size and type.

### Chunks produced per file

| File type | Avg chunks | Notes |
|---|---|---|
| Java source (small class, <10 methods) | 4–8 | 1 class chunk + N method chunks |
| Java source (large class, 10–50 methods) | 10–50 | Heavy on graph edges too |
| Markdown | 2–4 | 1 chunk per heading section; large sections split at 4 KB |
| OpenAPI/YAML spec | 1 per endpoint | 1 chunk per HTTP operation |
| JSON / config / plain text | 1–3 | Tika plain-text extraction, minimal structure |

**Rule of thumb:** 1 file ≈ 5 chunks on average across a typical Java/Markdown repo.

### Disk per chunk

| Component | Size per chunk |
|---|---|
| Lucene stored text field | ~500 B (snippet) |
| Lucene metadata fields (id, repo, path, fqn, …) | ~300 B |
| Lucene HNSW vector (384 floats × 4 B) | 1,536 B |
| HNSW graph connections overhead (M=16) | ~320 B |
| **Total per chunk** | **~2.7 KB** |

### Indexing CPU per chunk (bge-small, 4-core CPU)

| Step | Time |
|---|---|
| JavaParser parse + graph extraction | 2–10 ms/file (not per chunk) |
| ONNX embedding inference | 25–50 ms/chunk (single-threaded) |
| Lucene IndexWriter write | ~1 ms/chunk |
| **Total wall time per chunk (1 thread)** | **~30–55 ms** |

With `index-threads: 4`, throughput scales roughly linearly up to physical core count.

---

## Sizing table

### Legend

- **Min RAM** = minimum to avoid GC pressure and Lucene I/O thrashing
- **Rec RAM** = comfortable operating point with page cache headroom
- **Heap** = `-Xmx` flag (set `-Xms` to half this value)
- **Index time** = approximate full-reindex wall time at recommended thread count
- **Index size** = approximate Lucene data on disk after full indexing

| File count | Min RAM | Rec RAM | Heap (`-Xmx`) | Index threads | Full-reindex time | Index size on disk |
|---|---|---|---|---|---|---|
| **1 K** | 2 GB | 4 GB | 1 GB | 2 | ~3 min | ~14 MB |
| **5 K** | 3 GB | 6 GB | 2 GB | 4 | ~12 min | ~68 MB |
| **10 K** | 4 GB | 8 GB | 3 GB | 4–6 | ~25 min | ~135 MB |
| **20 K** | 6 GB | 12 GB | 4 GB | 6–8 | ~50 min | ~270 MB |
| **50 K** | 10 GB | 16 GB | 6 GB | 8–12 | ~2 h | ~675 MB |
| **100 K** | 16 GB | 24 GB | 8 GB | 12–16 | ~4 h | ~1.4 GB |

**Index size formula:** `files × 5 chunks/file × 2.7 KB/chunk ≈ files × 13.5 KB`

**Index time formula (4 threads):** `files × 5 chunks × 40 ms/chunk ÷ 4 threads ÷ 60 = minutes`

---

## JVM flags by tier

### 1 K – 5 K files (personal project / microservice)

```bash
java -Xms512m -Xmx2g \
  -XX:+UseG1GC \
  -jar kira.jar \
  --kira.executor.index-threads=2 \
  --kira.full-reindex.batch-size=200
```

### 10 K – 20 K files (medium monorepo / platform team)

```bash
java -Xms2g -Xmx4g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar kira.jar \
  --kira.executor.index-threads=6 \
  --kira.full-reindex.batch-size=500
```

### 50 K – 100 K files (large monorepo / enterprise)

```bash
java -Xms4g -Xmx8g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1HeapRegionSize=16m \
  -jar kira.jar \
  --kira.executor.index-threads=12 \
  --kira.full-reindex.batch-size=1000
```

---

## CPU requirements

### Minimum viable CPU

- **4 physical cores** for repos up to 20 K files.
- Kira does not require modern AVX-512 or special SIMD — bge-small ONNX runs on any x86-64 or ARM64 CPU.

### Recommended CPU by tier

| File count | Min cores | Rec cores | Notes |
|---|---|---|---|
| ≤ 5 K | 2 | 4 | Laptop / small VM |
| 5 K – 20 K | 4 | 8 | Standard workstation |
| 20 K – 50 K | 8 | 12 | Indexing otherwise takes hours |
| 50 K – 100 K | 12 | 16 | Overnight indexing on 12 cores |

**ONNX intraOpNumThreads:** Set this to physical core count in `application.yml`:

```yaml
kira:
  embedding:
    # leave blank to auto-detect; or set explicitly:
    # intra-op-threads: 8
```

Hyperthreading does not help ONNX inference meaningfully. Set threads to physical cores, not logical processors.

### Incremental indexing CPU

Incremental (git-diff) indexing processes only changed files. For a typical 20-file commit on a 20 K file repo:

- 20 files × 5 chunks × 40 ms = ~4 seconds on a single thread.
- Incremental indexing can run at `index-threads: 2` with no user-visible delay.

---

## RAM breakdown

### Fixed overhead (always present)

| Component | RAM |
|---|---|
| JVM base + Spring Boot context | 300–500 MB |
| ONNX Runtime session (bge-small) | 200–350 MB |
| ONNX Runtime session (reranker, if enabled) | 100–200 MB |
| JGraphT code graph (1 K Java files ≈ 5 K nodes + 20 K edges) | ~4 MB |
| JGraphT code graph (10 K Java files) | ~40 MB |
| JGraphT code graph (100 K Java files) | ~400 MB |
| Lucene IndexWriter write buffer | 256 MB (default) |
| **Total fixed (reranker disabled)** | **~800 MB – 1.1 GB** |

### Variable overhead (scales with file count)

| Component | Per 1 K files | Per 10 K files | Per 100 K files |
|---|---|---|---|
| Lucene `MMapDirectory` page cache | OS manages; budget 1× index size | ~135 MB | ~1.4 GB |
| JVM heap during full reindex (batch queue) | ~100 MB | ~500 MB | ~2 GB |
| Graph edges in JGraphT | ~3 MB | ~30 MB | ~300 MB |

**Heap versus page cache:** JVM heap and OS page cache compete for RAM. Set `-Xmx` no higher than 60% of total RAM so the OS has room to cache the Lucene MMapDirectory. On a 16 GB machine, use `-Xmx8g` at most.

### RAM by tier (summary)

| File count | JVM heap (`-Xmx`) | OS page cache budget | Total RAM needed |
|---|---|---|---|
| 1 K | 1 GB | 0.5 GB | 2 GB |
| 5 K | 2 GB | 0.5 GB | 4 GB |
| 10 K | 3 GB | 1 GB | 6–8 GB |
| 20 K | 4 GB | 1.5 GB | 8–10 GB |
| 50 K | 6 GB | 2 GB | 12–16 GB |
| 100 K | 8 GB | 3 GB | 16–24 GB |

---

## Disk requirements

### Storage layout

```
~/.kira/data/
├── lucene/           ← largest; HNSW vectors dominate
├── graph/            ← JGraphT serialization (small)
├── models/           ← ONNX model files (fixed)
└── checkpoint.json   ← negligible
```

### Model file sizes

| Model | Size on disk |
|---|---|
| bge-small-en-v1.5 (embedding) | 22 MB |
| tokenizer.json | ~500 KB |
| reranker model (if enabled) | 80–120 MB |
| **Total models** | **~120–145 MB** |

### Index size by file count

| File count | Lucene index | Graph data | Total data dir |
|---|---|---|---|
| 1 K | ~14 MB | ~1 MB | ~20 MB |
| 5 K | ~68 MB | ~5 MB | ~75 MB |
| 10 K | ~135 MB | ~10 MB | ~150 MB |
| 20 K | ~270 MB | ~20 MB | ~300 MB |
| 50 K | ~675 MB | ~50 MB | ~750 MB |
| 100 K | ~1.4 GB | ~100 MB | ~1.6 GB |

**Storage type:** Local NVMe SSD strongly recommended for the Lucene index. Lucene uses memory-mapped I/O (`MMapDirectory`); random-access latency on spinning disk or network storage will multiply search latency 5–20×.

---

## Concurrency knobs

```yaml
kira:
  executor:
    index-threads: 4        # parallel file-level workers during full reindex
  full-reindex:
    batch-size: 200         # files committed to Lucene per batch (affects RAM during indexing)
  candidate-k: 50           # BM25+KNN candidate pool for hybrid search (affects search RAM)
  spec-max-ops: 200         # max OpenAPI operations scanned per spec-vs-impl check
```

### index-threads vs. cores

| Physical cores | Recommended index-threads |
|---|---|
| 2 | 1–2 |
| 4 | 3–4 |
| 8 | 6–7 |
| 12 | 9–10 |
| 16 | 12–13 |

Reserve 1–2 cores for the JVM GC, Lucene writer, and OS overhead.

### batch-size vs. RAM

Larger batches keep more chunks in the write buffer before committing. Useful on high-RAM machines to reduce commit overhead.

| Available heap | Recommended batch-size |
|---|---|
| ≤ 1 GB | 50–100 |
| 2–4 GB | 200–500 |
| 4–8 GB | 500–1000 |

---

## Reranker impact

Reranking is disabled by default (`kira.reranker.enabled: false`). When enabled:

| Metric | Impact |
|---|---|
| Additional RAM | +100–200 MB (second ONNX session) |
| Search latency delta | +30–150 ms per query (50 candidates × ~3 ms each) |
| CPU during search | Spikes to 1–2 cores per query |
| Index time | No change (reranking is search-time only) |

Enable reranking only after staging the model files and only when precision matters more than latency.

---

## Minimum machine specifications

### Local dev / personal project (≤ 5 K files)

- CPU: 4 cores, any modern x86-64 or ARM64
- RAM: 4 GB
- Disk: 2 GB free (SSD preferred)
- OS: Linux, macOS, or Windows with JDK 21

### Team / CI server (5 K – 20 K files)

- CPU: 8 cores
- RAM: 8 GB
- Disk: 4 GB free (NVMe SSD)
- OS: Linux recommended

### Large monorepo (20 K – 100 K files)

- CPU: 12–16 cores
- RAM: 16–24 GB
- Disk: 10 GB free (NVMe SSD)
- OS: Linux

---

## Quick estimation command

Run before sizing a new repo:

```bash
# Count indexable files
find <repo-path> \
  -path "*/.git" -prune -o \
  -path "*/target" -prune -o \
  -path "*/build" -prune -o \
  -path "*/out" -prune -o \
  -path "*/node_modules" -prune -o \
  -type f \( \
  -name "*.java" -o -name "*.md" -o -name "*.yaml" -o \
  -name "*.yml" -o -name "*.json" -o -name "*.xml" -o \
  -name "*.txt" -o -name "*.html" \
\) | wc -l
```

This estimate should roughly match the files allowed by `kira.accept.include` after `.gitignore` and `kira.accept.exclude` are applied.

Then apply:

```
chunks  = files × 5
disk_MB = chunks × 2.7 KB / 1024
heap_GB = max(1, disk_MB / 256)   (rough)
ram_GB  = heap_GB × 2 + 1         (heap + page cache + fixed overhead)
index_minutes = chunks × 40ms / index_threads / 60000
```

Example: 15 K files:
- chunks = 75 000
- disk = ~203 MB
- heap = ~800 MB → round up to 2 GB
- ram = ~5 GB → use 6 GB machine
- index time (6 threads) = 75 000 × 40 ms ÷ 6 ÷ 60 000 ≈ **8 minutes**
