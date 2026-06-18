# Kira — Branch Modes & Auto-Sync Plan

> **Prerequisite:** Implement `2026-06-16-file-acceptance.md` first. This plan assumes `AcceptConfig`, `FilterRegistry`, and the updated `IndexService`/`FullReindexService` signatures from that plan are already in place.

## Codex Invocation

```bash
codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
  -C <kira-install-dir> \
  "Read and implement the plan at docs/superpowers/plans/2026-06-16-branch-modes-autosync.md. The companion plan 2026-06-16-file-acceptance.md must already be applied. Create all new files shown. Apply all modifications to existing files exactly as specified. Run: mvn compile -q && mvn test -q at the end and confirm both pass."
```

---

**Goal:** Support `SINGLE` (one fixed branch) or `MULTI` (multiple branches, glob patterns) per repo. Auto-sync detects new commits and runs incremental indexing on a configurable interval. Every `Chunk` carries a `branch` field so search can filter by branch.

---

## Step 1 — Expand `ApplicationProps` with branch config records

`RepoConfig` was added as a stub in the file-acceptance plan. Replace `ApplicationProps.java` with the full version including branch records:

- [ ] Replace `src/main/java/com/acme/airetrieval/config/ApplicationProps.java` with:

```java
package com.acme.airetrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "kira")
public record ApplicationProps(
    Path dataDir,
    Path indexDir,
    Path checkpointFile,
    Path modelsDir,
    int maxSearchResults,
    int defaultSearchK,
    Embedding embedding,
    Reranker reranker,
    TokenBudgetConfig tokenBudget,
    Executor executor,
    Graph graph,
    FullReindex fullReindex,
    AcceptConfig accept,
    List<RepoConfig> repos
) {
    public record Executor(int indexThreads) {}
    public record Embedding(Path modelPath, Path tokenizerPath, int dim) {}
    public record Reranker(Path modelPath, Path tokenizerPath, boolean enabled) {}
    public record TokenBudgetConfig(int defaultBudgetTokens, int charsPerToken) {}
    public record Graph(String engine, Path kuzuDir) {}
    public record FullReindex(int batchSize, int parallelFiles) {}

    public record AcceptConfig(List<String> include, List<String> exclude) {
        public static AcceptConfig acceptAll() {
            return new AcceptConfig(List.of(), List.of());
        }
        public static AcceptConfig defaults() {
            return new AcceptConfig(
                List.of("**/*.java", "**/*.md", "**/*.markdown", "**/*.yml", "**/*.yaml",
                        "**/*.json", "**/*.pdf", "**/*.docx", "**/*.html", "**/*.txt"),
                List.of("**/target/**", "**/.git/**", "**/.idea/**", "**/*.class",
                        "**/*.jar", "**/*.war", "**/node_modules/**", "**/.DS_Store")
            );
        }
    }

    public record RepoConfig(
        String id,
        Path path,
        BranchConfig branches,
        AutoSyncConfig autoSync,
        AcceptConfig accept
    ) {}

    public record BranchConfig(BranchMode mode, List<String> tracked) {
        public static BranchConfig singleMain() {
            return new BranchConfig(BranchMode.SINGLE, List.of("main"));
        }
    }

    public enum BranchMode { SINGLE, MULTI }

    public record AutoSyncConfig(boolean enabled, int intervalSeconds) {
        public static AutoSyncConfig disabled() {
            return new AutoSyncConfig(false, 300);
        }
    }
}
```

- [ ] Add example repos config to `src/main/resources/application.yml` (after existing accept block):

```yaml
  # Example — single repo, single branch, auto-sync every 5 minutes
  # repos:
  #   - id: my-service
  #     path: /home/user/projects/my-service
  #     branches:
  #       mode: SINGLE
  #       tracked:
  #         - main
  #     auto-sync:
  #       enabled: true
  #       interval-seconds: 300
  #     accept:
  #       include:
  #         - "src/**/*.java"
  #         - "docs/**/*.md"
  #       exclude:
  #         - "src/test/**"
```

---

## Step 2 — Add `branch` field to `Chunk`

- [ ] Replace `src/main/java/com/acme/airetrieval/ingest/model/Chunk.java` with:

```java
package com.acme.airetrieval.ingest.model;

import java.util.List;

public record Chunk(
    String id,
    String repo,
    String branch,
    String path,
    Domain domain,
    String type,
    String fqn,
    String title,
    String section,
    List<String> symbols,
    String gitSha,
    String contentHash,
    String lang,
    String text,
    float[] vector
) {}
```

- [ ] Update every `Chunk` constructor call in the codebase to pass `null` for `branch` (position 3, after `repo`). Files that construct Chunk directly:
  - `src/main/java/com/acme/airetrieval/ingest/parser/MarkdownParser.java`
  - `src/main/java/com/acme/airetrieval/ingest/parser/DocumentParser.java`
  - `src/main/java/com/acme/airetrieval/ingest/parser/JavaSourceParser.java`
  - `src/main/java/com/acme/airetrieval/ingest/parser/ApiSpecParser.java`
  - `src/main/java/com/acme/airetrieval/ingest/IndexService.java`
  - Any test files that build Chunk directly

  For each Chunk constructor call, insert `null` as the third argument (after `repo`, before `path`).

---

## Step 3 — Update `LuceneIndexer` to store and index `branch`

- [ ] Replace `src/main/java/com/acme/airetrieval/index/LuceneIndexer.java` with:

```java
package com.acme.airetrieval.index;

import com.acme.airetrieval.ingest.model.Chunk;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.MMapDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LuceneIndexer implements AutoCloseable {
    private final StandardAnalyzer analyzer = new StandardAnalyzer();
    private final IndexWriter writer;

    public LuceneIndexer(Path indexDir) throws IOException {
        Files.createDirectories(indexDir);
        var config = new IndexWriterConfig(analyzer).setRAMBufferSizeMB(256);
        writer = new IndexWriter(MMapDirectory.open(indexDir), config);
    }

    public void upsert(Chunk chunk) throws IOException {
        Document doc = new Document();
        add(doc, "id", chunk.id());
        add(doc, "repo", chunk.repo());
        add(doc, "branch", chunk.branch());
        add(doc, "path", chunk.path());
        add(doc, "domain", chunk.domain() == null ? null : chunk.domain().name());
        add(doc, "type", chunk.type());
        add(doc, "fqn", chunk.fqn());
        add(doc, "title", chunk.title());
        add(doc, "section", chunk.section());
        add(doc, "git_sha", chunk.gitSha());
        add(doc, "content_hash", chunk.contentHash());
        add(doc, "lang", chunk.lang());
        doc.add(new TextField("text", chunk.text() == null ? "" : chunk.text(), Field.Store.YES));
        if (chunk.vector() != null) {
            doc.add(new KnnFloatVectorField("vector", chunk.vector(), VectorSimilarityFunction.COSINE));
        }
        writer.updateDocument(new Term("id", chunk.id()), doc);
    }

    public void deleteByPath(String path) throws IOException {
        writer.deleteDocuments(new Term("path", path));
    }

    public void deleteByPathAndBranch(String path, String branch) throws IOException {
        if (branch == null) {
            deleteByPath(path);
            return;
        }
        var query = new BooleanQuery.Builder()
            .add(new TermQuery(new Term("path", path)), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term("branch", branch)), BooleanClause.Occur.MUST)
            .build();
        writer.deleteDocuments(query);
    }

    public void commit() throws IOException {
        writer.commit();
    }

    public IndexWriter getWriter() {
        return writer;
    }

    private static void add(Document doc, String name, String value) {
        if (value != null) doc.add(new StringField(name, value, Field.Store.YES));
    }

    @Override
    public void close() throws IOException {
        writer.close();
        analyzer.close();
    }
}
```

---

## Step 4 — Add `branch` to `SearchFilter` and `NrtLuceneSearcher`

- [ ] Replace `src/main/java/com/acme/airetrieval/index/model/SearchFilter.java` with:

```java
package com.acme.airetrieval.index.model;

public record SearchFilter(String repo, String domain, String type, String path, String branch) {
    public SearchFilter(String repo, String domain, String type, String path) {
        this(repo, domain, type, path, null);
    }
}
```

- [ ] In `src/main/java/com/acme/airetrieval/index/NrtLuceneSearcher.java`, update `buildFilter` to handle `branch`. Replace the `buildFilter` method with:

```java
private Query buildFilter(SearchFilter filter) {
    if (filter == null) return null;
    BooleanQuery.Builder b = new BooleanQuery.Builder();
    boolean any = false;
    if (filter.repo() != null)    { b.add(new TermQuery(new Term("repo",   filter.repo())),   BooleanClause.Occur.FILTER); any = true; }
    if (filter.domain() != null)  { b.add(new TermQuery(new Term("domain", filter.domain())), BooleanClause.Occur.FILTER); any = true; }
    if (filter.type() != null)    { b.add(new TermQuery(new Term("type",   filter.type())),   BooleanClause.Occur.FILTER); any = true; }
    if (filter.path() != null)    { b.add(new TermQuery(new Term("path",   filter.path())),   BooleanClause.Occur.FILTER); any = true; }
    if (filter.branch() != null)  { b.add(new TermQuery(new Term("branch", filter.branch())), BooleanClause.Occur.FILTER); any = true; }
    return any ? b.build() : null;
}
```

---

## Step 5 — Create `CheckpointStore`

- [ ] Create `src/main/java/com/acme/airetrieval/ingest/CheckpointStore.java`:

```java
package com.acme.airetrieval.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class CheckpointStore {
    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, String> state;

    public CheckpointStore(Path file, ObjectMapper mapper) throws IOException {
        this.file = file;
        this.mapper = mapper;
        if (Files.exists(file)) {
            state = mapper.readValue(file.toFile(), new TypeReference<HashMap<String, String>>() {});
        } else {
            state = new HashMap<>();
        }
    }

    public Optional<String> get(String repoId, String branch) {
        return Optional.ofNullable(state.get(key(repoId, branch)));
    }

    public void put(String repoId, String branch, String sha) {
        state.put(key(repoId, branch), sha);
    }

    public void flush() throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), state);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(state);
    }

    private String key(String repoId, String branch) {
        return (branch == null || branch.isBlank()) ? repoId : repoId + ":" + branch;
    }
}
```

---

## Step 6 — Create `BranchResolver`

- [ ] Create `src/main/java/com/acme/airetrieval/ingest/BranchResolver.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.BranchConfig;
import com.acme.airetrieval.config.ApplicationProps.BranchMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

public final class BranchResolver {

    public List<String> resolve(Path repoDir, BranchConfig config) throws Exception {
        if (config == null || config.tracked() == null || config.tracked().isEmpty()) {
            return List.of("main");
        }
        if (config.mode() == BranchMode.SINGLE) {
            return List.of(config.tracked().get(0));
        }
        // MULTI: expand glob patterns against local branch refs
        try (Git git = Git.open(repoDir.toFile())) {
            List<Ref> refs = git.branchList().call();
            var fs = FileSystems.getDefault();
            List<PathMatcher> matchers = config.tracked().stream()
                .map(p -> fs.getPathMatcher("glob:" + p))
                .toList();
            return refs.stream()
                .map(r -> r.getName().replace("refs/heads/", ""))
                .filter(name -> matchers.stream().anyMatch(m -> m.matches(Path.of(name))))
                .toList();
        }
    }

    public String headSha(Path repoDir, String branch) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            var repo = git.getRepository();
            Ref ref = repo.findRef("refs/heads/" + branch);
            if (ref == null) throw new IllegalArgumentException("Branch not found: " + branch);
            return ref.getObjectId().getName();
        }
    }
}
```

---

## Step 7 — Add `branch` param to `IndexService` and `FullReindexService`

These files already have `FilterRegistry` from the companion plan. Now add the `branch` parameter so each index operation is scoped to a branch.

- [ ] Replace `src/main/java/com/acme/airetrieval/ingest/IndexService.java` with:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.graph.GraphExtractor;
import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.model.Change;
import com.acme.airetrieval.ingest.model.Chunk;
import com.acme.airetrieval.ingest.parser.ApiSpecParser;
import com.acme.airetrieval.ingest.parser.DocumentParser;
import com.acme.airetrieval.ingest.parser.JavaSourceParser;
import com.acme.airetrieval.ingest.parser.MarkdownParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IndexService {
    private final LuceneIndexer indexer;
    private final NrtLuceneSearcher searcher;
    private final EmbeddingModel embeddingModel;
    private final GraphExtractor graphExtractor;
    private final FilterRegistry filterRegistry;
    private final GitChangeDetector changeDetector = new GitChangeDetector();
    private final SourceClassifier classifier = new SourceClassifier();
    private final MarkdownParser markdownParser = new MarkdownParser();
    private final DocumentParser documentParser = new DocumentParser();
    private final JavaSourceParser javaSourceParser = new JavaSourceParser();
    private final ApiSpecParser apiSpecParser = new ApiSpecParser();

    public IndexService(LuceneIndexer indexer, NrtLuceneSearcher searcher,
                        EmbeddingModel embeddingModel, GraphExtractor graphExtractor,
                        FilterRegistry filterRegistry) {
        this.indexer = indexer;
        this.searcher = searcher;
        this.embeddingModel = embeddingModel;
        this.graphExtractor = graphExtractor;
        this.filterRegistry = filterRegistry;
    }

    // branch-aware incremental index (new — used by BranchSyncService)
    public IndexResult indexIncremental(Path repoPath, String repo, String branch,
                                        String fromSha, String toSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        List<Change> changes = changeDetector.detectChanges(repoPath, fromSha, toSha).stream()
            .filter(c -> c.type() == Change.ChangeType.DELETE || filter.accept(c.path()))
            .toList();
        int indexed = 0, deleted = 0, skipped = 0;
        for (Change change : changes) {
            if (change.type() == Change.ChangeType.DELETE) {
                indexer.deleteByPathAndBranch(change.path(), branch);
                deleted++;
                continue;
            }
            Path file = repoPath.resolve(change.path());
            if (!Files.isRegularFile(file)) continue;
            String text = Files.readString(file);
            String existingHash = searcher.getContentHash(change.path() + "#body").orElse(null);
            String newHash = MarkdownParser.hash(text);
            if (newHash.equals(existingHash)) {
                skipped++;
                continue;
            }
            indexer.deleteByPathAndBranch(change.path(), branch);
            indexed += indexAndEmbed(repo, branch, change.path(), toSha, text);
        }
        indexer.commit();
        searcher.maybeReopen();
        return new IndexResult(indexed, deleted, skipped, toSha);
    }

    // branch-unaware incremental index (backward compat — REST API callers)
    public IndexResult indexIncremental(Path repoPath, String repo, String fromSha, String toSha) throws Exception {
        return indexIncremental(repoPath, repo, null, fromSha, toSha);
    }

    // branch-aware single-file index
    public IndexResult indexFile(String repo, String branch, Path repoDir,
                                 String relativePath, String gitSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        Path file = repoDir.resolve(relativePath);
        if (!Files.isRegularFile(file)) return new IndexResult(0, 0, 0, gitSha);
        if (!filter.accept(relativePath)) return new IndexResult(0, 0, 1, gitSha);
        String text = Files.readString(file);
        indexer.deleteByPathAndBranch(relativePath, branch);
        int count = indexAndEmbed(repo, branch, relativePath, gitSha, text);
        indexer.commit();
        searcher.maybeReopen();
        return new IndexResult(count, 0, 0, gitSha);
    }

    // branch-unaware single-file index (backward compat)
    public IndexResult indexFile(String repo, Path repoDir, String relativePath, String gitSha) throws Exception {
        return indexFile(repo, null, repoDir, relativePath, gitSha);
    }

    private int indexAndEmbed(String repo, String branch, String relativePath,
                               String gitSha, String text) throws Exception {
        List<Chunk> chunks = new ArrayList<>();
        switch (classifier.classify(relativePath)) {
            case MARKDOWN -> chunks.addAll(markdownParser.parse(repo, relativePath, gitSha, text));
            case DOCUMENT -> chunks.addAll(documentParser.parseText(repo, relativePath, gitSha, text));
            case JAVA -> {
                var parsed = javaSourceParser.parse(repo, relativePath, gitSha, text);
                chunks.addAll(parsed.chunks());
                graphExtractor.apply(parsed.events());
            }
            case API_SPEC -> {
                var parsed = apiSpecParser.parse(repo, relativePath, gitSha, text);
                chunks.addAll(parsed.chunks());
                graphExtractor.apply(parsed.events());
            }
            case IGNORE -> { return 0; }
        }
        for (Chunk chunk : chunks) {
            float[] vector = embeddingModel.embed(chunk.text());
            indexer.upsert(new Chunk(chunk.id(), chunk.repo(), branch, chunk.path(),
                chunk.domain(), chunk.type(), chunk.fqn(), chunk.title(), chunk.section(),
                chunk.symbols(), chunk.gitSha(), chunk.contentHash(), chunk.lang(),
                chunk.text(), vector));
        }
        return chunks.size();
    }

    public record IndexResult(int indexed, int deleted, int skipped, String toSha) {
        public IndexResult(int chunksIndexed) {
            this(chunksIndexed, 0, 0, null);
        }
    }
}
```

- [ ] Replace `src/main/java/com/acme/airetrieval/ingest/FullReindexService.java` with:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.index.NrtLuceneSearcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class FullReindexService {
    private final IndexService indexService;
    private final NrtLuceneSearcher searcher;
    private final ExecutorService executor;
    private final FilterRegistry filterRegistry;
    private final SourceClassifier classifier = new SourceClassifier();
    private final int batchSize;

    public FullReindexService(IndexService indexService, NrtLuceneSearcher searcher,
                              ExecutorService executor, ApplicationProps props,
                              FilterRegistry filterRegistry) {
        this.indexService = indexService;
        this.searcher = searcher;
        this.executor = executor;
        this.filterRegistry = filterRegistry;
        this.batchSize = Math.max(1, props.fullReindex().batchSize());
    }

    // branch-aware full reindex (used by BranchSyncService)
    public FullReindexResult reindex(String repo, String branch, Path repoDir, String gitSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        List<Path> files;
        try (var stream = Files.walk(repoDir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(file -> {
                    String rel = repoDir.relativize(file).toString().replace('\\', '/');
                    return filter.accept(rel)
                        && classifier.classify(rel) != SourceClassifier.SourceType.IGNORE;
                })
                .toList();
        }

        AtomicInteger indexed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        List<Future<IndexService.IndexResult>> batch = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            String rel = repoDir.relativize(file).toString().replace('\\', '/');
            batch.add(executor.submit(() -> indexService.indexFile(repo, branch, repoDir, rel, gitSha)));
            boolean lastFile = i == files.size() - 1;
            if (batch.size() >= batchSize || lastFile) {
                for (Future<IndexService.IndexResult> future : batch) {
                    IndexService.IndexResult result = future.get();
                    indexed.addAndGet(result.indexed());
                    skipped.addAndGet(result.skipped());
                }
                batch.clear();
                searcher.maybeReopen();
            }
        }
        return new FullReindexResult(indexed.get(), skipped.get(), files.size());
    }

    // branch-unaware full reindex (backward compat — REST API)
    public FullReindexResult reindex(String repo, Path repoDir, String gitSha) throws Exception {
        return reindex(repo, null, repoDir, gitSha);
    }

    public record FullReindexResult(int indexed, int skipped, int totalFiles) {}
}
```

---

## Step 8 — Create `BranchSyncService`

- [ ] Create `src/main/java/com/acme/airetrieval/ingest/BranchSyncService.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.RepoConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BranchSyncService {
    private final BranchResolver branchResolver;
    private final CheckpointStore checkpointStore;
    private final IndexService indexService;
    private final FullReindexService fullReindexService;

    public BranchSyncService(BranchResolver branchResolver, CheckpointStore checkpointStore,
                              IndexService indexService, FullReindexService fullReindexService) {
        this.branchResolver = branchResolver;
        this.checkpointStore = checkpointStore;
        this.indexService = indexService;
        this.fullReindexService = fullReindexService;
    }

    public Map<String, IndexService.IndexResult> sync(RepoConfig repo) throws Exception {
        List<String> branches = branchResolver.resolve(repo.path(), repo.branches());
        Map<String, IndexService.IndexResult> results = new LinkedHashMap<>();
        for (String branch : branches) {
            String headSha = branchResolver.headSha(repo.path(), branch);
            String fromSha = checkpointStore.get(repo.id(), branch).orElse(null);
            IndexService.IndexResult result;
            if (fromSha == null) {
                var r = fullReindexService.reindex(repo.id(), branch, repo.path(), headSha);
                result = new IndexService.IndexResult(r.indexed(), 0, r.skipped(), headSha);
            } else if (fromSha.equals(headSha)) {
                result = new IndexService.IndexResult(0, 0, 0, headSha);
            } else {
                result = indexService.indexIncremental(repo.path(), repo.id(), branch, fromSha, headSha);
            }
            checkpointStore.put(repo.id(), branch, headSha);
            results.put(branch, result);
        }
        checkpointStore.flush();
        return results;
    }
}
```

---

## Step 9 — Create `BranchSyncScheduler`

- [ ] Create `src/main/java/com/acme/airetrieval/ingest/BranchSyncScheduler.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps;
import com.acme.airetrieval.config.ApplicationProps.RepoConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

@Component
public class BranchSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(BranchSyncScheduler.class);

    private final ApplicationProps props;
    private final BranchSyncService syncService;
    private final CheckpointStore checkpointStore;
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> futures = new HashMap<>();
    private final Map<String, Instant> lastSync = new HashMap<>();

    public BranchSyncScheduler(ApplicationProps props, BranchSyncService syncService,
                                CheckpointStore checkpointStore, TaskScheduler taskScheduler) {
        this.props = props;
        this.syncService = syncService;
        this.checkpointStore = checkpointStore;
        this.taskScheduler = taskScheduler;
    }

    @PostConstruct
    public void scheduleAll() {
        List<RepoConfig> repos = props.repos();
        if (repos == null || repos.isEmpty()) return;
        for (RepoConfig repo : repos) {
            if (repo.autoSync() != null && repo.autoSync().enabled()) {
                Duration interval = Duration.ofSeconds(repo.autoSync().intervalSeconds());
                ScheduledFuture<?> future = taskScheduler.scheduleAtFixedRate(
                    () -> runSync(repo), Instant.now().plus(interval), interval);
                futures.put(repo.id(), future);
                log.info("Scheduled auto-sync for repo '{}' every {}s",
                    repo.id(), repo.autoSync().intervalSeconds());
            }
        }
    }

    public Map<String, IndexService.IndexResult> triggerSync(String repoId) throws Exception {
        RepoConfig repo = props.repos().stream()
            .filter(r -> r.id().equals(repoId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown repo: " + repoId));
        Map<String, IndexService.IndexResult> result = syncService.sync(repo);
        lastSync.put(repoId, Instant.now());
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new HashMap<>();
        out.put("checkpoints", checkpointStore.snapshot());
        out.put("lastSync", lastSync);
        return out;
    }

    private void runSync(RepoConfig repo) {
        Instant start = Instant.now();
        try {
            syncService.sync(repo);
            lastSync.put(repo.id(), Instant.now());
            log.info("Auto-sync '{}' done in {}ms",
                repo.id(), Duration.between(start, Instant.now()).toMillis());
        } catch (Exception e) {
            log.error("Auto-sync failed for repo '{}'", repo.id(), e);
        }
    }
}
```

---

## Step 10 — Add `TaskScheduler` bean to `ExecutorConfig`

- [ ] Replace `src/main/java/com/acme/airetrieval/config/ExecutorConfig.java` with:

```java
package com.acme.airetrieval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService indexingExecutor(ApplicationProps props) {
        return Executors.newFixedThreadPool(Math.max(1, props.executor().indexThreads()));
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("kira-sync-");
        scheduler.initialize();
        return scheduler;
    }
}
```

---

## Step 11 — Wire new beans in `IngestConfig`

- [ ] Replace `src/main/java/com/acme/airetrieval/config/IngestConfig.java` with:

```java
package com.acme.airetrieval.config;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.graph.GraphExtractor;
import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.BranchResolver;
import com.acme.airetrieval.ingest.BranchSyncService;
import com.acme.airetrieval.ingest.CheckpointStore;
import com.acme.airetrieval.ingest.FilterRegistry;
import com.acme.airetrieval.ingest.FullReindexService;
import com.acme.airetrieval.ingest.IndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

@Configuration
public class IngestConfig {

    @Bean
    public FilterRegistry filterRegistry(ApplicationProps props) {
        return new FilterRegistry(props.accept(), props.repos());
    }

    @Bean
    public CheckpointStore checkpointStore(ApplicationProps props, ObjectMapper objectMapper) throws IOException {
        return new CheckpointStore(props.checkpointFile(), objectMapper);
    }

    @Bean
    public BranchResolver branchResolver() {
        return new BranchResolver();
    }

    @Bean
    public IndexService indexService(LuceneIndexer indexer, NrtLuceneSearcher searcher,
                                     EmbeddingModel embeddingModel, GraphExtractor graphExtractor,
                                     FilterRegistry filterRegistry) {
        return new IndexService(indexer, searcher, embeddingModel, graphExtractor, filterRegistry);
    }

    @Bean
    public FullReindexService fullReindexService(IndexService indexService, NrtLuceneSearcher searcher,
                                                  ExecutorService executor, ApplicationProps props,
                                                  FilterRegistry filterRegistry) {
        return new FullReindexService(indexService, searcher, executor, props, filterRegistry);
    }

    @Bean
    public BranchSyncService branchSyncService(BranchResolver branchResolver, CheckpointStore checkpointStore,
                                                IndexService indexService, FullReindexService fullReindexService) {
        return new BranchSyncService(branchResolver, checkpointStore, indexService, fullReindexService);
    }
}
```

Note: `BranchSyncScheduler` is a `@Component` and auto-wired by Spring; no explicit bean needed in `IngestConfig`.

---

## Step 12 — Update `IndexController` and `IndexRequest`

- [ ] Replace `src/main/java/com/acme/airetrieval/api/dto/IndexRequest.java` with:

```java
package com.acme.airetrieval.api.dto;

public record IndexRequest(
    String repo,
    String repoDir,
    String path,
    String gitSha,
    String fromSha,
    String toSha,
    String branch       // nullable — defaults to null (branch-unaware)
) {}
```

- [ ] Replace `src/main/java/com/acme/airetrieval/api/IndexController.java` with:

```java
package com.acme.airetrieval.api;

import com.acme.airetrieval.api.dto.IndexRequest;
import com.acme.airetrieval.ingest.BranchSyncScheduler;
import com.acme.airetrieval.ingest.FullReindexService;
import com.acme.airetrieval.ingest.IndexService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/index")
public class IndexController {
    private final IndexService indexService;
    private final FullReindexService fullReindexService;
    private final BranchSyncScheduler syncScheduler;

    public IndexController(IndexService indexService, FullReindexService fullReindexService,
                           BranchSyncScheduler syncScheduler) {
        this.indexService = indexService;
        this.fullReindexService = fullReindexService;
        this.syncScheduler = syncScheduler;
    }

    @PostMapping
    public IndexService.IndexResult index(@RequestBody IndexRequest request) throws Exception {
        return indexService.indexFile(request.repo(), request.branch(),
            Path.of(request.repoDir()), request.path(), request.gitSha());
    }

    @PostMapping("/incremental")
    public IndexService.IndexResult incremental(@RequestBody IndexRequest request) throws Exception {
        return indexService.indexIncremental(Path.of(request.repoDir()), request.repo(),
            request.branch(), request.fromSha(), request.toSha());
    }

    @PostMapping("/full")
    public FullReindexService.FullReindexResult full(@RequestBody IndexRequest request) throws Exception {
        return fullReindexService.reindex(request.repo(), request.branch(),
            Path.of(request.repoDir()), request.gitSha());
    }

    @PostMapping("/sync/{repoId}")
    public Map<String, IndexService.IndexResult> sync(@PathVariable String repoId) throws Exception {
        return syncScheduler.triggerSync(repoId);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return syncScheduler.status();
    }
}
```

---

## Step 13 — Update `McpTools` to accept optional `branch` filter

- [ ] In `src/main/java/com/acme/airetrieval/mcp/McpTools.java`, update the three search tools to accept `branch`. Replace the three search tool methods with:

```java
@Tool(description = "Search code chunks by semantic + BM25 hybrid search")
public List<SearchHit> search_code(
    @ToolParam(description = "natural language query") String query,
    @ToolParam(description = "repository name filter, or null for all repos") String repo,
    @ToolParam(description = "branch filter, or null for all branches") String branch,
    @ToolParam(description = "max results to return") int k) throws Exception {
    return retrieval.hybrid(query, new SearchFilter(repo, "CODE", null, null, branch), k);
}

@Tool(description = "Search knowledge/docs chunks by semantic + BM25 hybrid search")
public List<SearchHit> search_knowledge(
    @ToolParam(description = "natural language query") String query,
    @ToolParam(description = "repository name filter, or null for all repos") String repo,
    @ToolParam(description = "branch filter, or null for all branches") String branch,
    @ToolParam(description = "max results to return") int k) throws Exception {
    return retrieval.hybrid(query, new SearchFilter(repo, "KNOWLEDGE", null, null, branch), k);
}

@Tool(description = "Semantic + BM25 hybrid search across both code and knowledge")
public List<SearchHit> semantic_search(
    @ToolParam(description = "natural language query") String query,
    @ToolParam(description = "repository name filter, or null for all repos") String repo,
    @ToolParam(description = "branch filter, or null for all branches") String branch,
    @ToolParam(description = "max results to return") int k) throws Exception {
    return retrieval.hybrid(query, new SearchFilter(repo, null, null, null, branch), k);
}
```

Also update `answer_context` to pass `branch = null`:
```java
@Tool(description = "Retrieve reranked, compacted answer context within a token budget")
public String answer_context(
    @ToolParam(description = "natural language query") String query,
    @ToolParam(description = "repository name filter, or null for all repos") String repo,
    @ToolParam(description = "max token budget for returned context") int budgetTokens) throws Exception {
    return retrieval.answerContext(query, new SearchFilter(repo, null, null, null, null), budgetTokens);
}
```

---

## Step 14 — Update `SearchRequest` and `SearchController`

- [ ] Replace `src/main/java/com/acme/airetrieval/api/dto/SearchRequest.java` with:

```java
package com.acme.airetrieval.api.dto;

public record SearchRequest(String query, String repo, String domain, String type,
                            String path, String branch, Integer k, String mode) {}
```

- [ ] In `src/main/java/com/acme/airetrieval/api/SearchController.java`, update the `SearchFilter` construction to pass `request.branch()`. Find the line that constructs `SearchFilter` and change it to:
  ```java
  new SearchFilter(request.repo(), request.domain(), request.type(), request.path(), request.branch())
  ```

---

## Step 15 — Tests

- [ ] Create `src/test/java/com/acme/airetrieval/ingest/CheckpointStoreTest.java`:

```java
package com.acme.airetrieval.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointStoreTest {

    @TempDir Path tmp;

    @Test
    void putGetFlushReload() throws Exception {
        Path file = tmp.resolve("checkpoint.json");
        var store = new CheckpointStore(file, new ObjectMapper());

        assertTrue(store.get("repo1", "main").isEmpty());
        store.put("repo1", "main", "abc123");
        store.put("repo1", "develop", "def456");
        store.flush();

        var reloaded = new CheckpointStore(file, new ObjectMapper());
        assertEquals("abc123", reloaded.get("repo1", "main").orElseThrow());
        assertEquals("def456", reloaded.get("repo1", "develop").orElseThrow());
    }

    @Test
    void nullBranch_usesRepoIdAsKey() throws Exception {
        Path file = tmp.resolve("checkpoint.json");
        var store = new CheckpointStore(file, new ObjectMapper());
        store.put("repo1", null, "sha1");
        assertEquals("sha1", store.get("repo1", null).orElseThrow());
    }

    @Test
    void snapshot_isUnmodifiable() throws Exception {
        var store = new CheckpointStore(tmp.resolve("checkpoint.json"), new ObjectMapper());
        store.put("r", "b", "s");
        var snap = store.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));
    }
}
```

- [ ] Create `src/test/java/com/acme/airetrieval/ingest/BranchResolverTest.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.BranchConfig;
import com.acme.airetrieval.config.ApplicationProps.BranchMode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchResolverTest {

    @TempDir Path tmp;

    @Test
    void single_returnsConfiguredBranch() throws Exception {
        var resolver = new BranchResolver();
        var config = new BranchConfig(BranchMode.SINGLE, List.of("main"));
        // repoDir not opened for SINGLE mode — path just needs to exist
        var result = resolver.resolve(tmp, config);
        assertEquals(List.of("main"), result);
    }

    @Test
    void multi_expandsGlobAgainstLocalRefs() throws Exception {
        // init a real git repo with two branches
        try (Git git = Git.init().setDirectory(tmp.toFile()).call()) {
            Path file = tmp.resolve("README.md");
            file.toFile().createNewFile();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").call();
            git.branchCreate().setName("feature/auth").call();
            git.branchCreate().setName("feature/search").call();
        }

        var resolver = new BranchResolver();
        var config = new BranchConfig(BranchMode.MULTI, List.of("feature/*"));
        var result = resolver.resolve(tmp, config);

        assertTrue(result.contains("feature/auth"));
        assertTrue(result.contains("feature/search"));
        assertFalse(result.contains("master") || result.contains("main"));
    }
}
```

---

## Verification

- [ ] Run `mvn compile -q` — must succeed.
- [ ] Run `mvn test -q` — all tests must pass.
- [ ] Confirm `GET /api/v1/index/status` returns `{"checkpoints":{}, "lastSync":{}}` on a fresh start.
