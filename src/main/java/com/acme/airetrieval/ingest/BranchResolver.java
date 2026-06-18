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
