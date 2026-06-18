# Kira — Configurable File Acceptance Plan

> **Companion plan:** `2026-06-16-branch-modes-autosync.md` builds on top of this — implement this plan first.

## Codex Invocation

```bash
codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
  -C <kira-install-dir> \
  "Read and implement the plan at docs/superpowers/plans/2026-06-16-file-acceptance.md. Create all new files shown. Apply all modifications to existing files exactly as specified. Run: mvn compile -q && mvn test -q at the end and confirm both pass."
```

---

**Goal:** Replace `SourceClassifier`'s hard-coded `IGNORE` logic with a configurable include/exclude glob filter per repo. A global default applies when no per-repo override exists.

**Filter order:**
1. `FileAcceptanceFilter.accept(path)` — glob include/exclude (new, configurable)
2. `SourceClassifier.classify(path)` — extension → parser type (unchanged)

DELETE changes bypass the acceptance filter so removed files are always cleaned up.

---

## Step 1 — Add `AcceptConfig` to `ApplicationProps` and update `application.yml`

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

    // RepoConfig is extended in the companion plan (branch-modes-autosync).
    // Define it here as a stub so both plans share the same record.
    public record RepoConfig(String id, Path path, AcceptConfig accept) {}
}
```

- [ ] Add to `src/main/resources/application.yml` under the `kira:` block:

```yaml
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
      - "**/.git/**"
      - "**/.idea/**"
      - "**/*.class"
      - "**/*.jar"
      - "**/*.war"
      - "**/node_modules/**"
      - "**/.DS_Store"

  repos: []
```

---

## Step 2 — Create `FileAcceptanceFilter`

- [ ] Create `src/main/java/com/acme/airetrieval/ingest/FileAcceptanceFilter.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.AcceptConfig;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

public final class FileAcceptanceFilter {
    private final List<PathMatcher> includes;
    private final List<PathMatcher> excludes;

    public FileAcceptanceFilter(AcceptConfig config) {
        var fs = FileSystems.getDefault();
        AcceptConfig cfg = config != null ? config : AcceptConfig.acceptAll();
        this.includes = cfg.include().stream()
            .map(p -> fs.getPathMatcher("glob:" + p))
            .toList();
        this.excludes = cfg.exclude().stream()
            .map(p -> fs.getPathMatcher("glob:" + p))
            .toList();
    }

    public boolean accept(String relativePath) {
        Path p = Path.of(relativePath.replace('\\', '/'));
        boolean included = includes.isEmpty() || includes.stream().anyMatch(m -> m.matches(p));
        boolean excluded = excludes.stream().anyMatch(m -> m.matches(p));
        return included && !excluded;
    }
}
```

---

## Step 3 — Create `FilterRegistry`

- [ ] Create `src/main/java/com/acme/airetrieval/ingest/FilterRegistry.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.AcceptConfig;
import com.acme.airetrieval.config.ApplicationProps.RepoConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FilterRegistry {
    private final FileAcceptanceFilter defaultFilter;
    private final Map<String, FileAcceptanceFilter> byRepo;

    public FilterRegistry(AcceptConfig defaultConfig, List<RepoConfig> repos) {
        AcceptConfig eff = defaultConfig != null ? defaultConfig : AcceptConfig.defaults();
        this.defaultFilter = new FileAcceptanceFilter(eff);
        this.byRepo = new HashMap<>();
        if (repos != null) {
            for (RepoConfig repo : repos) {
                if (repo.accept() != null) {
                    byRepo.put(repo.id(), new FileAcceptanceFilter(repo.accept()));
                }
            }
        }
    }

    public FileAcceptanceFilter forRepo(String repoId) {
        if (repoId == null) return defaultFilter;
        return byRepo.getOrDefault(repoId, defaultFilter);
    }

    public FileAcceptanceFilter defaultFilter() {
        return defaultFilter;
    }
}
```

---

## Step 4 — Update `IndexService` to check acceptance filter

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

    public IndexResult indexIncremental(Path repoPath, String repo, String fromSha, String toSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        List<Change> changes = changeDetector.detectChanges(repoPath, fromSha, toSha).stream()
            .filter(c -> c.type() == Change.ChangeType.DELETE || filter.accept(c.path()))
            .toList();
        int indexed = 0;
        int deleted = 0;
        int skipped = 0;
        for (Change change : changes) {
            if (change.type() == Change.ChangeType.DELETE) {
                indexer.deleteByPath(change.path());
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
            indexer.deleteByPath(change.path());
            indexed += indexAndEmbed(repo, change.path(), toSha, text);
        }
        indexer.commit();
        searcher.maybeReopen();
        return new IndexResult(indexed, deleted, skipped, toSha);
    }

    public IndexResult indexFile(String repo, Path repoDir, String relativePath, String gitSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        Path file = repoDir.resolve(relativePath);
        if (!Files.isRegularFile(file)) return new IndexResult(0, 0, 0, gitSha);
        if (!filter.accept(relativePath)) return new IndexResult(0, 0, 1, gitSha);
        String text = Files.readString(file);
        indexer.deleteByPath(relativePath);
        int count = indexAndEmbed(repo, relativePath, gitSha, text);
        indexer.commit();
        searcher.maybeReopen();
        return new IndexResult(count, 0, 0, gitSha);
    }

    private int indexAndEmbed(String repo, String relativePath, String gitSha, String text) throws Exception {
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
            indexer.upsert(new Chunk(chunk.id(), chunk.repo(), chunk.path(), chunk.domain(), chunk.type(),
                chunk.fqn(), chunk.title(), chunk.section(), chunk.symbols(), chunk.gitSha(),
                chunk.contentHash(), chunk.lang(), chunk.text(), vector));
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

---

## Step 5 — Update `FullReindexService` to check acceptance filter

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

    public FullReindexResult reindex(String repo, Path repoDir, String gitSha) throws Exception {
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
            batch.add(executor.submit(() -> indexService.indexFile(repo, repoDir, rel, gitSha)));
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

    public record FullReindexResult(int indexed, int skipped, int totalFiles) {}
}
```

---

## Step 6 — Wire `FilterRegistry` in `IngestConfig`

- [ ] Replace `src/main/java/com/acme/airetrieval/config/IngestConfig.java` with:

```java
package com.acme.airetrieval.config;

import com.acme.airetrieval.embed.EmbeddingModel;
import com.acme.airetrieval.graph.GraphExtractor;
import com.acme.airetrieval.index.LuceneIndexer;
import com.acme.airetrieval.index.NrtLuceneSearcher;
import com.acme.airetrieval.ingest.FilterRegistry;
import com.acme.airetrieval.ingest.FullReindexService;
import com.acme.airetrieval.ingest.IndexService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

@Configuration
public class IngestConfig {

    @Bean
    public FilterRegistry filterRegistry(ApplicationProps props) {
        return new FilterRegistry(props.accept(), props.repos());
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
}
```

---

## Step 7 — Tests

- [ ] Create `src/test/java/com/acme/airetrieval/ingest/FileAcceptanceFilterTest.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.AcceptConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileAcceptanceFilterTest {

    @Test
    void acceptAll_whenIncludeEmpty() {
        var f = new FileAcceptanceFilter(AcceptConfig.acceptAll());
        assertTrue(f.accept("src/main/Foo.java"));
        assertTrue(f.accept("README.md"));
        assertTrue(f.accept("some/random/file.xyz"));
    }

    @Test
    void includeGlob_acceptsMatch() {
        var f = new FileAcceptanceFilter(new AcceptConfig(List.of("**/*.java"), List.of()));
        assertTrue(f.accept("src/main/com/Foo.java"));
        assertFalse(f.accept("README.md"));
    }

    @Test
    void excludeWinsOverInclude() {
        var f = new FileAcceptanceFilter(new AcceptConfig(
            List.of("**/*.java"),
            List.of("**/target/**")
        ));
        assertFalse(f.accept("target/classes/Foo.java"));
        assertTrue(f.accept("src/main/Foo.java"));
    }

    @Test
    void defaultConfig_excludesTargetAndGit() {
        var f = new FileAcceptanceFilter(AcceptConfig.defaults());
        assertFalse(f.accept("target/Foo.class"));
        assertFalse(f.accept(".git/config"));
        assertTrue(f.accept("src/main/Foo.java"));
        assertTrue(f.accept("docs/README.md"));
    }

    @Test
    void defaultConfig_excludesClassFiles() {
        var f = new FileAcceptanceFilter(AcceptConfig.defaults());
        assertFalse(f.accept("out/Foo.class"));
        assertFalse(f.accept("lib/mylib.jar"));
    }

    @Test
    void handlesBackslashPaths() {
        var f = new FileAcceptanceFilter(new AcceptConfig(List.of("**/*.java"), List.of()));
        assertTrue(f.accept("src\\main\\Foo.java"));
    }
}
```

- [ ] Create `src/test/java/com/acme/airetrieval/ingest/FilterRegistryTest.java`:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps.AcceptConfig;
import com.acme.airetrieval.config.ApplicationProps.RepoConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilterRegistryTest {

    @Test
    void returnsDefaultFilter_whenNoRepoOverride() {
        var registry = new FilterRegistry(AcceptConfig.defaults(), List.of());
        var filter = registry.forRepo("unknown-repo");
        assertNotNull(filter);
        assertSame(registry.defaultFilter(), filter);
    }

    @Test
    void returnsRepoFilter_whenOverrideConfigured() {
        var repoAccept = new AcceptConfig(List.of("src/**/*.java"), List.of());
        var repo = new RepoConfig("my-service", Path.of("/tmp/my-service"), repoAccept);
        var registry = new FilterRegistry(AcceptConfig.defaults(), List.of(repo));

        var filter = registry.forRepo("my-service");
        assertNotSame(registry.defaultFilter(), filter);
        assertTrue(filter.accept("src/main/Foo.java"));
        assertFalse(filter.accept("README.md")); // not in include glob
    }

    @Test
    void returnsDefaultFilter_forNullRepoId() {
        var registry = new FilterRegistry(AcceptConfig.defaults(), List.of());
        assertSame(registry.defaultFilter(), registry.forRepo(null));
    }

    @Test
    void handlesNullReposList() {
        var registry = new FilterRegistry(AcceptConfig.defaults(), null);
        assertNotNull(registry.forRepo("anything"));
    }
}
```

---

## Verification

- [ ] Run `mvn compile -q` — must succeed with no errors.
- [ ] Run `mvn test -q` — all existing tests must still pass; new tests must pass.
