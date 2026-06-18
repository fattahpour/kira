package com.acme.airetrieval.embed;

public interface EmbeddingModel extends AutoCloseable {
    float[] embed(String text) throws Exception;

    default float[][] embedBatch(String[] texts) throws Exception {
        float[][] out = new float[texts.length][];
        for (int i = 0; i < texts.length; i++) out[i] = embed(texts[i]);
        return out;
    }

    @Override
    default void close() throws Exception {}
}
