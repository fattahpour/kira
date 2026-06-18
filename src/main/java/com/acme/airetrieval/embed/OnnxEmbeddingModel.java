package com.acme.airetrieval.embed;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class OnnxEmbeddingModel implements EmbeddingModel {
    private static final int MAX_TOKENS = 512;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public OnnxEmbeddingModel(Path modelPath, Path tokenizerPath) throws Exception {
        env = OrtEnvironment.getEnvironment();
        var options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        session = env.createSession(modelPath.toString(), options);
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    }

    @Override
    public float[] embed(String text) throws Exception {
        Encoding encoding = tokenizer.encode(text == null ? "" : text, true, true);
        long[] ids = truncate(encoding.getIds());
        long[] mask = truncate(encoding.getAttentionMask());
        long[] typeIds = new long[ids.length];
        long[] shape = {1L, ids.length};
        try (var inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
             var attentionMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
             var tokenTypeIds = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape);
             var result = session.run(Map.of("input_ids", inputIds, "attention_mask", attentionMask, "token_type_ids", tokenTypeIds))) {
            float[][][] hidden = (float[][][]) result.get(0).getValue();
            return normalize(meanPool(hidden[0], mask));
        }
    }

    private static long[] truncate(long[] values) {
        return values.length <= MAX_TOKENS ? values : Arrays.copyOf(values, MAX_TOKENS);
    }

    private static float[] meanPool(float[][] embeddings, long[] mask) {
        float[] out = new float[embeddings[0].length];
        int count = 0;
        for (int i = 0; i < embeddings.length; i++) {
            if (mask[i] != 1L) continue;
            count++;
            for (int j = 0; j < out.length; j++) out[j] += embeddings[i][j];
        }
        if (count > 0) for (int j = 0; j < out.length; j++) out[j] /= count;
        return out;
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm) + 1e-12;
        for (int i = 0; i < vector.length; i++) vector[i] = (float) (vector[i] / norm);
        return vector;
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
