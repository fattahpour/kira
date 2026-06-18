package com.acme.airetrieval.ingest.model;

public record EndpointInfo(String method, String path, String operationId, String handlerFqn) {}
