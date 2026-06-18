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
