package com.acme.airetrieval.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GitIgnoreFilterTest {
    @TempDir
    Path repoDir;

    @Test
    void disabled_neverIgnores() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "*.log\n");
        GitIgnoreFilter filter = GitIgnoreFilter.disabled();
        assertThat(filter.isIgnored("app.log")).isFalse();
        assertThat(filter.isIgnored("src/Foo.java")).isFalse();
    }

    @Test
    void rootGitignore_matchesFlatPattern() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "*.log\n*.class\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("app.log")).isTrue();
        assertThat(filter.isIgnored("Main.class")).isTrue();
        assertThat(filter.isIgnored("src/Main.java")).isFalse();
    }

    @Test
    void rootGitignore_matchesDirectoryPattern() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "target/\nbuild/\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("target/classes/Foo.class")).isTrue();
        assertThat(filter.isIgnored("build/libs/app.jar")).isTrue();
        assertThat(filter.isIgnored("src/main/Foo.java")).isFalse();
    }

    @Test
    void nestedGitignore_overridesRoot() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "*.log\n");
        Path subdir = Files.createDirectories(repoDir.resolve("logs"));
        Files.writeString(subdir.resolve(".gitignore"), "!important.log\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("app.log")).isTrue();
        assertThat(filter.isIgnored("logs/debug.log")).isTrue();
        assertThat(filter.isIgnored("logs/important.log")).isFalse();
    }

    @Test
    void noGitignore_nothingIgnored() throws IOException {
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("src/Foo.java")).isFalse();
        assertThat(filter.isIgnored("anything/at/all.txt")).isFalse();
    }

    @Test
    void backslashPaths_normalizedCorrectly() throws IOException {
        Files.writeString(repoDir.resolve(".gitignore"), "target/\n");
        GitIgnoreFilter filter = GitIgnoreFilter.forRepo(repoDir);
        assertThat(filter.isIgnored("target\\classes\\Foo.class")).isTrue();
    }
}
