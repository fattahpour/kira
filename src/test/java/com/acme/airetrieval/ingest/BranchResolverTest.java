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

        var result = resolver.resolve(tmp, config);

        assertEquals(List.of("main"), result);
    }

    @Test
    void multi_expandsGlobAgainstLocalRefs() throws Exception {
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
