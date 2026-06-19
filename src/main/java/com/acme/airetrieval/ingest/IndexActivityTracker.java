package com.acme.airetrieval.ingest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IndexActivityTracker {
    private final ConcurrentHashMap<String, Boolean> active = new ConcurrentHashMap<>();

    public void start(String repo, String branch) {
        active.put(key(repo, branch), Boolean.TRUE);
    }

    public void stop(String repo, String branch) {
        active.remove(key(repo, branch));
    }

    public boolean isActive(String repo, String branch) {
        return active.containsKey(key(repo, branch));
    }

    public boolean anyActive() {
        return !active.isEmpty();
    }

    public Set<String> activeKeys() {
        return Set.copyOf(active.keySet());
    }

    private static String key(String repo, String branch) {
        return branch != null && !branch.isBlank() ? repo + ":" + branch : repo + ":";
    }
}
