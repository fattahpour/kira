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
            List.of("**/target/**", "target/**")
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
