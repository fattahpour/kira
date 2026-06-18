package com.acme.airetrieval.ingest.model;

public record Change(ChangeType type, String path) {
    public enum ChangeType { ADD, MODIFY, DELETE }
}
