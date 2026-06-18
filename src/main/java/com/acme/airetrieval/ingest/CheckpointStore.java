package com.acme.airetrieval.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class CheckpointStore {
    private final Path file;
    private final ObjectMapper mapper;
    private final Map<String, String> state;

    public CheckpointStore(Path file, ObjectMapper mapper) throws IOException {
        this.file = file;
        this.mapper = mapper;
        if (Files.exists(file)) {
            state = mapper.readValue(file.toFile(), new TypeReference<HashMap<String, String>>() {});
        } else {
            state = new HashMap<>();
        }
    }

    public synchronized Optional<String> get(String repoId, String branch) {
        return Optional.ofNullable(state.get(key(repoId, branch)));
    }

    public synchronized void put(String repoId, String branch, String sha) {
        state.put(key(repoId, branch), sha);
    }

    public synchronized void flush() throws IOException {
        Map<String, String> copy = new HashMap<>(state);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), copy);
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(state);
    }

    private String key(String repoId, String branch) {
        return (branch == null || branch.isBlank()) ? repoId : repoId + ":" + branch;
    }
}
