package com.acme.airetrieval.ingest;

import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Respects .gitignore rules during file-walk indexing.
 */
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
                    } catch (IOException ignored) {
                    }
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
            } catch (IOException ignored) {
            }
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
            if (node == null) {
                continue;
            }

            Path relToNodePath = relPath.subpath(depth, nameCount);
            int relNameCount = relToNodePath.getNameCount();
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
