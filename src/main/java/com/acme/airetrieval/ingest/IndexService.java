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
