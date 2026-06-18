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
        var repo = new RepoConfig("my-service", Path.of("/tmp/my-service"), null, null, repoAccept);
        var registry = new FilterRegistry(AcceptConfig.defaults(), List.of(repo));

        var filter = registry.forRepo("my-service");
        assertNotSame(registry.defaultFilter(), filter);
        assertTrue(filter.accept("src/main/Foo.java"));
        assertFalse(filter.accept("README.md"));
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
