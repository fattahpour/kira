package com.acme.airetrieval.embed;

import java.nio.charset.StandardCharsets;

public final class DeterministicEmbeddingModel implements EmbeddingModel {
    private final int dim;

    public DeterministicEmbeddingModel(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dim];
        for (String token : (text == null ? "" : text.toLowerCase()).split("[^a-z0-9]+")) {
            if (token.isBlank()) continue;
            int hash = java.util.Arrays.hashCode(token.getBytes(StandardCharsets.UTF_8));
            int index = Math.floorMod(hash, dim);
            vector[index] += 1.0f;
        }
        return l2(vector);
    }

    private static float[] l2(float[] vector) {
        double norm = 0.0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0.0) return vector;
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / norm);
        return vector;
    }
}
