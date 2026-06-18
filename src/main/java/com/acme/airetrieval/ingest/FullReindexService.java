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
        FileAcceptanceFilter filter = filterRegistry.forRepo(repo);
        GitIgnoreFilter gitFilter = respectGitignore
            ? GitIgnoreFilter.forRepo(repoDir)
            : GitIgnoreFilter.disabled();
        List<Path> files;
        try (var stream = Files.walk(repoDir)) {
            files = stream.filter(Files::isRegularFile)
                .filter(file -> {
                    String rel = repoDir.relativize(file).toString().replace('\\', '/');
                    return !gitFilter.isIgnored(rel)
                        && filter.accept(rel)
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
