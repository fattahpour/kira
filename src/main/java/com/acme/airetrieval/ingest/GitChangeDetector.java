package com.acme.airetrieval.ingest;

import com.acme.airetrieval.ingest.model.Change;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.nio.file.Path;
import java.util.List;

public final class GitChangeDetector {
    public List<Change> detectChanges(Path repoDir, String fromSha, String toSha) throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            ObjectId oldTree = tree(git, fromSha);
            ObjectId newTree = tree(git, toSha);
            try (var reader = git.getRepository().newObjectReader()) {
                CanonicalTreeParser oldParser = new CanonicalTreeParser();
                oldParser.reset(reader, oldTree);
                CanonicalTreeParser newParser = new CanonicalTreeParser();
                newParser.reset(reader, newTree);
                return git.diff().setOldTree(oldParser).setNewTree(newParser).call().stream()
                    .map(this::change)
                    .toList();
            }
        }
    }

    private ObjectId tree(Git git, String sha) throws Exception {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            return walk.parseCommit(git.getRepository().resolve(sha)).getTree().getId();
        }
    }

    private Change change(DiffEntry entry) {
        return switch (entry.getChangeType()) {
            case DELETE -> new Change(Change.ChangeType.DELETE, entry.getOldPath());
            case ADD, COPY -> new Change(Change.ChangeType.ADD, entry.getNewPath());
            default -> new Change(Change.ChangeType.MODIFY, entry.getNewPath());
        };
    }
}
