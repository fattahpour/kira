package com.acme.airetrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties(prefix = "kira")
public record ApplicationProps(
    Path dataDir,
    Path indexDir,
    Path checkpointFile,
    Path modelsDir,
    int maxSearchResults,
    int defaultSearchK,
    int candidateK,
    int specMaxOps,
    Embedding embedding,
    Reranker reranker,
    TokenBudgetConfig tokenBudget,
    Executor executor,
    Graph graph,
    FullReindex fullReindex,
    AcceptConfig accept,
    List<RepoConfig> repos,
    boolean respectGitignore
) {
    public record Executor(int indexThreads) {}
    public record Embedding(Path modelPath, Path tokenizerPath, int dim) {}
    public record Reranker(Path modelPath, Path tokenizerPath, boolean enabled) {}
    public record TokenBudgetConfig(int defaultBudgetTokens, int charsPerToken) {}
    public record Graph(String engine, Path kuzuDir) {}
    public record FullReindex(int batchSize, int parallelFiles) {}

    public record AcceptConfig(List<String> include, List<String> exclude) {
        public AcceptConfig {
            include = include == null ? List.of() : List.copyOf(include);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }

        public static AcceptConfig acceptAll() {
            return new AcceptConfig(List.of(), List.of());
        }

        public static AcceptConfig defaults() {
            return new AcceptConfig(
                List.of("**/*.java", "**/*.md", "**/*.markdown", "**/*.yml", "**/*.yaml",
                        "**/*.json", "**/*.pdf", "**/*.docx", "**/*.html", "**/*.txt"),
                List.of("**/target/**", "target/**", "**/.git/**", ".git/**", "**/.idea/**",
                        "**/*.class", "**/*.jar", "**/*.war", "**/node_modules/**",
                        "node_modules/**", "**/.DS_Store")
            );
        }
    }

    public record RepoConfig(
        String id,
        Path path,
        BranchConfig branches,
        AutoSyncConfig autoSync,
        AcceptConfig accept
    ) {}

    public record BranchConfig(BranchMode mode, List<String> tracked) {
        public BranchConfig {
            mode = mode == null ? BranchMode.SINGLE : mode;
            tracked = tracked == null ? List.of() : List.copyOf(tracked);
        }

        public static BranchConfig singleMain() {
            return new BranchConfig(BranchMode.SINGLE, List.of("main"));
        }
    }

    public enum BranchMode { SINGLE, MULTI }

    public record AutoSyncConfig(boolean enabled, int intervalSeconds) {
        public static AutoSyncConfig disabled() {
            return new AutoSyncConfig(false, 300);
        }
    }
}
