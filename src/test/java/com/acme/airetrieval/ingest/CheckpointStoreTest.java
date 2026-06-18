package com.acme.airetrieval.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointStoreTest {

    @TempDir Path tmp;

    @Test
    void putGetFlushReload() throws Exception {
        Path file = tmp.resolve("checkpoint.json");
        var store = new CheckpointStore(file, new ObjectMapper());

        assertTrue(store.get("repo1", "main").isEmpty());

        store.put("repo1", "main", "abc123");
        store.put("repo1", "develop", "def456");
        store.flush();

        var reloaded = new CheckpointStore(file, new ObjectMapper());
        assertEquals("abc123", reloaded.get("repo1", "main").orElseThrow());
        assertEquals("def456", reloaded.get("repo1", "develop").orElseThrow());
    }

    @Test
    void nullBranch_usesRepoIdAsKey() throws Exception {
        Path file = tmp.resolve("checkpoint.json");
        var store = new CheckpointStore(file, new ObjectMapper());
        store.put("repo1", null, "sha1");
        assertEquals("sha1", store.get("repo1", null).orElseThrow());
    }

    @Test
    void snapshot_isUnmodifiable() throws Exception {
        var store = new CheckpointStore(tmp.resolve("checkpoint.json"), new ObjectMapper());
        store.put("r", "b", "s");
        var snap = store.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));
    }
}
