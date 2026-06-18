# Kira — Respect `.gitignore` During Indexing

Kira's `FullReindexService` walks the repo filesystem but does not respect `.gitignore` rules. Files
excluded by git (build artifacts, secrets, IDE files) silently enter the index. This plan adds a
`GitIgnoreFilter` that reads `.gitignore` files via JGit's `IgnoreNode` and applies them as an
additional gate in the file-walk and single-file-index paths.

`IndexService.indexIncremental` is NOT changed — git diff only returns tracked file changes, so
ignored files never appear there.

## Codex Invocation

```bash
codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check \
  -C <kira-install-dir> \
  "Read and apply the plan at docs/superpowers/plans/2026-06-17-gitignore-respect.md. For each step: verify whether the change is already applied, apply it if not. Run: mvn compile -q && mvn test -q at the end and confirm both pass."
```

---

## Steps

### Step 1 — Add `respectGitignore` to `ApplicationProps`

**File:** `src/main/java/com/acme/airetrieval/config/ApplicationProps.java`

Add one field to the record. Add it after `repos`:

```java
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
    List<RepoConfig> repos,
    boolean respectGitignore
) {
    // ... all existing nested records unchanged ...
}
```

No other changes to this file.

---

### Step 2 — Add `kira.respect-gitignore: true` to `application.yml`

**File:** `src/main/resources/application.yml`

Add one line inside the `kira:` block (after `repos: []`):

```yaml
  respect-gitignore: true
```

---

### Step 3 — Create `GitIgnoreFilter`

**File:** `src/main/java/com/acme/airetrieval/ingest/GitIgnoreFilter.java`

**Verify file already exists** — Codex likely already created it. If present, **do not overwrite**.

The correct implementation uses `IgnoreNode.MatchResult` (not `checkIgnored`) and includes an
inner ancestor-directory loop (checking each path component with `isDirectory=true`) so that
directory patterns like `target/` correctly match files under `target/classes/Foo.class`.

If the file does not exist, create it with this target state:

```java
package com.acme.airetrieval.ingest;

import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class GitIgnoreFilter {
    private final Path repoRoot;
    private final Map<Path, IgnoreNode> ignoreNodes;

    private GitIgnoreFilter(Path repoRoot, Map<Path, IgnoreNode> ignoreNodes) {
        this.repoRoot = repoRoot;
        this.ignoreNodes = ignoreNodes;
    }

    public static GitIgnoreFilter forRepo(Path repoDir) throws IOException {
        Path root = repoDir.toAbsolutePath().normalize();
        Map<Path, IgnoreNode> nodes = new LinkedHashMap<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.getFileName() != null
                    && ".gitignore".equals(path.getFileName().toString())
                    && !isInsideDotGit(root, path))
                .sorted()
                .forEach(gitignorePath -> {
                    IgnoreNode node = new IgnoreNode();
                    try (InputStream in = Files.newInputStream(gitignorePath)) {
                        node.parse(in);
                        if (!node.getRules().isEmpty()) {
                            nodes.put(gitignorePath.getParent().toAbsolutePath().normalize(), node);
                        }
                    } catch (IOException ignored) {}
                });
        }

        Path exclude = root.resolve(".git/info/exclude");
        if (Files.isRegularFile(exclude)) {
            IgnoreNode node = new IgnoreNode();
            try (InputStream in = Files.newInputStream(exclude)) {
                node.parse(in);
                if (!node.getRules().isEmpty()) {
                    nodes.merge(root, node, (existing, extra) -> {
                        existing.getRules().addAll(extra.getRules());
                        return existing;
                    });
                }
            } catch (IOException ignored) {}
        }

        return new GitIgnoreFilter(root, nodes);
    }

    public static GitIgnoreFilter disabled() {
        return new GitIgnoreFilter(Path.of("/"), Map.of());
    }

    public boolean isIgnored(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        Path relPath = Path.of(normalized);
        int nameCount = relPath.getNameCount();
        IgnoreNode.MatchResult result = IgnoreNode.MatchResult.CHECK_PARENT;

        for (int depth = 0; depth < nameCount; depth++) {
            Path dir = depth == 0
                ? repoRoot
                : repoRoot.resolve(relPath.subpath(0, depth)).toAbsolutePath().normalize();
            IgnoreNode node = ignoreNodes.get(dir);
            if (node == null) continue;

            Path relToNodePath = relPath.subpath(depth, nameCount);
            int relNameCount = relToNodePath.getNameCount();
            // Check each ancestor component as a directory so patterns like target/ match
            for (int i = 1; i < relNameCount; i++) {
                String ancestor = relToNodePath.subpath(0, i).toString().replace('\\', '/');
                IgnoreNode.MatchResult match = node.isIgnored(ancestor, true);
                if (match != IgnoreNode.MatchResult.CHECK_PARENT) {
                    result = match;
                }
            }
            String relToNode = relToNodePath.toString().replace('\\', '/');
            IgnoreNode.MatchResult match = node.isIgnored(relToNode, false);
            if (match != IgnoreNode.MatchResult.CHECK_PARENT) {
                result = match;
            }
        }

        return result == IgnoreNode.MatchResult.IGNORED;
    }

    private static boolean isInsideDotGit(Path root, Path path) {
        String rel = root.relativize(path).toString().replace('\\', '/');
        return rel.startsWith(".git/");
    }
}
```

---

### Step 4 — Apply `GitIgnoreFilter` in `FullReindexService`

**File:** `src/main/java/com/acme/airetrieval/ingest/FullReindexService.java`

Complete target state:

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
    private final boolean respectGitignore;

    public FullReindexService(IndexService indexService, NrtLuceneSearcher searcher,
                              ExecutorService executor, ApplicationProps props,
                              FilterRegistry filterRegistry) {
        this.indexService = indexService;
        this.searcher = searcher;
        this.executor = executor;
        this.filterRegistry = filterRegistry;
        this.batchSize = Math.max(1, props.fullReindex().batchSize());
        this.respectGitignore = props.respectGitignore();
    }

    public FullReindexResult reindex(String repo, String branch, Path repoDir, String gitSha) throws Exception {
        FileAcceptanceFilter acceptFilter = filterRegistry.forRepo(repo);
        GitIgnoreFilter gitFilter = respectGitignore
            ? GitIgnoreFilter.forRepo(repoDir)
            : GitIgnoreFilter.disabled();

        List<Path> files;
        try (var stream = Files.walk(repoDir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(file -> {
                    String rel = repoDir.relativize(file).toString().replace('\\', '/');
                    return !gitFilter.isIgnored(rel)
                        && acceptFilter.accept(rel)
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

    public FullReindexResult reindex(String repo, Path repoDir, String gitSha) throws Exception {
        return reindex(repo, null, repoDir, gitSha);
    }

    public record FullReindexResult(int indexed, int skipped, int totalFiles) {}
}
```

---

### Step 5 — Apply `GitIgnoreFilter` in `IndexService.indexFile`

**File:** `src/main/java/com/acme/airetrieval/ingest/IndexService.java`

Add `boolean respectGitignore` field, set in constructor from `ApplicationProps`, then add check inside `indexFile` before the acceptance filter.

Complete target state:

```java
package com.acme.airetrieval.ingest;

import com.acme.airetrieval.config.ApplicationProps;
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
    private final boolean respectGitignore;
    private final GitChangeDetector changeDetector = new GitChangeDetector();
    private final SourceClassifier classifier = new SourceClassifier();
    private final MarkdownParser markdownParser = new MarkdownParser();
    private final DocumentParser documentParser = new DocumentParser();
    private final JavaSourceParser javaSourceParser = new JavaSourceParser();
    private final ApiSpecParser apiSpecParser = new ApiSpecParser();

    public IndexService(LuceneIndexer indexer, NrtLuceneSearcher searcher,
                        EmbeddingModel embeddingModel, GraphExtractor graphExtractor,
                        FilterRegistry filterRegistry, boolean respectGitignore) {
        this.indexer = indexer;
        this.searcher = searcher;
        this.embeddingModel = embeddingModel;
        this.graphExtractor = graphExtractor;
        this.filterRegistry = filterRegistry;
        this.respectGitignore = respectGitignore;
    }

    public IndexResult indexIncremental(Path repoPath, String repo, String branch,
                                        String fromSha, String toSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        List<Change> changes = changeDetector.detectChanges(repoPath, fromSha, toSha).stream()
            .filter(c -> c.type() == Change.ChangeType.DELETE || filter.accept(c.path()))
            .toList();
        int indexed = 0;
        int deleted = 0;
        int skipped = 0;
        for (Change change : changes) {
            if (change.type() == Change.ChangeType.DELETE) {
                indexer.deleteByPathAndBranch(change.path(), branch);
                deleted++;
                continue;
            }
            Path file = repoPath.resolve(change.path());
            if (!Files.isRegularFile(file)) continue;
            String text = Files.readString(file);
            // Hash dedup only when branch-unaware: cross-branch hash leakage would cause false skips.
            if (branch == null) {
                String existingHash = searcher.getContentHash(change.path() + "#body").orElse(null);
                String newHash = MarkdownParser.hash(text);
                if (newHash.equals(existingHash)) {
                    skipped++;
                    continue;
                }
            }
            indexer.deleteByPathAndBranch(change.path(), branch);
            indexed += indexAndEmbed(repo, branch, change.path(), toSha, text);
        }
        indexer.commit();
        searcher.maybeReopen();
        return new IndexResult(indexed, deleted, skipped, toSha);
    }

    public IndexResult indexIncremental(Path repoPath, String repo, String fromSha, String toSha) throws Exception {
        return indexIncremental(repoPath, repo, null, fromSha, toSha);
    }

    public IndexResult indexFile(String repo, String branch, Path repoDir,
                                 String relativePath, String gitSha) throws Exception {
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        Path file = repoDir.resolve(relativePath);
        if (!Files.isRegularFile(file)) return new IndexResult(0, 0, 0, gitSha);
        if (respectGitignore) {
            GitIgnoreFilter gitFilter = GitIgnoreFilter.forRepo(repoDir);
            if (gitFilter.isIgnored(relativePath)) return new IndexResult(0, 0, 1, gitSha);
        }
        if (!filter.accept(relativePath)) return new IndexResult(0, 0, 1, gitSha);
        String text = Files.readString(file);
        indexer.deleteByPathAndBranch(relativePath, branch);
        int count = indexAndEmbed(repo, branch, relativePath, gitSha, text);
        indexer.commit();
        searcher.maybeReopen();
        return new IndexResult(count, 0, 0, gitSha);
    }

    public IndexResult indexFile(String repo, Path repoDir, String relativePath, String gitSha) throws Exception {
        return indexFile(repo, null, repoDir, relativePath, gitSha);
    }

    private int indexAndEmbed(String repo, String branch, String relativePath, String gitSha, String text) throws Exception {
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
            indexer.upsert(new Chunk(chunk.id(), chunk.repo(), branch, chunk.path(), chunk.domain(), chunk.type(),
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

### Step 6 — Update `IngestConfig` to pass `respectGitignore` to `IndexService`

**File:** `src/main/java/com/acme/airetrieval/config/IngestConfig.java`

`IndexService` now requires `boolean respectGitignore` as the 6th constructor arg.

Complete target state:

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
                                     FilterRegistry filterRegistry, ApplicationProps props) {
        return new IndexService(indexer, searcher, embeddingModel, graphExtractor,
                                filterRegistry, props.respectGitignore());
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

---

### Step 7 — Create `GitIgnoreFilterTest`

**File:** `src/test/java/com/acme/airetrieval/ingest/GitIgnoreFilterTest.java`

Complete target state:

```java
package com.acme.airetrieval.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitIgnoreFilterTest {

    @TempDir
    Path repoDir;

    @Test
    void disabled_neverIgnores() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "*.log\n");
        GitIgnoreFilter filter = GitIgnoreFilter.disabled();
        assertThat(filter.isIgnored("app.log")).isFalse();
        assertThat(filter.isIgnored("src/Foo.java")).isFalse();
    }

    @Test
    void rootGitignore_matchesFlatPattern() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "*.log\n*.class\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("app.log")).isTrue();
        assertThat(filter.isIgnored("Main.class")).isTrue();
        assertThat(filter.isIgnored("src/Main.java")).isFalse();
    }

    @Test
    void rootGitignore_matchesDirectoryPattern() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "target/\nbuild/\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("target/classes/Foo.class")).isTrue();
        assertThat(filter.isIgnored("build/libs/app.jar")).isTrue();
        assertThat(filter.isIgnored("src/main/Foo.java")).isFalse();
    }

    @Test
    void nestedGitignore_overridesRoot() throws IOException {
        // Root ignores all .log files; subdir un-ignores its own .log (negation)
        Files.writeString(repoDir.resolve(".gitignore"), "*.log\n");
        Path subdir = Files.createDirectories(repoDir.resolve("logs"));
        Files.writeString(subdir.resolve(".gitignore"), "!important.log\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("app.log")).isTrue();
        assertThat(filter.isIgnored("logs/debug.log")).isTrue();
        // Negation in nested .gitignore un-ignores that file
        assertThat(filter.isIgnored("logs/important.log")).isFalse();
    }

    @Test
    void noGitignore_nothingIgnored() throws IOException {
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("src/Foo.java")).isFalse();
        assertThat(filter.isIgnored("anything/at/all.txt")).isFalse();
    }

    @Test
    void backslashPaths_normalizedCorrectly() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "target/\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        // Windows-style path separator must be handled
        assertThat(filter.isIgnored("target\\classes\\Foo.class")).isTrue();
    }
}
```

---

### Step 8 — Update tests that construct `IndexService` directly

Any test that calls `new IndexService(...)` with 5 args must pass a 6th boolean. Search for `new IndexService(` in the test tree and add `false` (disable gitignore in unit tests) as the last argument.

```bash
grep -rn "new IndexService(" src/test/
```

For each match, change:
```java
new IndexService(indexer, searcher, model, graph, registry)
```
to:
```java
new IndexService(indexer, searcher, model, graph, registry, false)
```

---

## Verification

- [ ] `mvn compile -q` — zero errors
- [ ] `mvn test -q` — all tests pass (expect 49+ total: 43 existing + 6 new `GitIgnoreFilterTest`)

## Design Notes

**Why per-reindex, not cached?**
`.gitignore` files change with commits. Creating `GitIgnoreFilter` at the start of each `reindex()` call costs one filesystem walk — negligible vs. the indexing work that follows. Caching would require invalidation logic keyed on git SHA, adding complexity for no measurable benefit.

**Why not applied to `indexIncremental`?**
`GitChangeDetector.detectChanges` runs `git diff`, which only surfaces changes to tracked files. Git already excludes ignored files from its tracking, so they never appear in the diff list.

**Why the `disabled()` factory?**
`respectGitignore: false` is a valid production escape hatch (e.g., indexing a repo whose `.gitignore` aggressively excludes docs). `disabled()` returns a zero-allocation no-op rather than wrapping every call in an `if`.

**Gate order in `FullReindexService`:**
1. `gitFilter.isIgnored()` — git semantics, cheapest (HashMap lookup after initial parse)
2. `acceptFilter.accept()` — config globs, PathMatcher
3. `classifier.classify() != IGNORE` — type detection by extension

Git-ignored files are filtered first since they're the cheapest and most common exclusion.
