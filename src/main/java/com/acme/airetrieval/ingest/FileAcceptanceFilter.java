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
