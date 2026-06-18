package com.acme.airetrieval.embed;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public final class Reranker implements AutoCloseable {
    private static final int MAX_TOKENS = 512;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public Reranker(Path modelPath, Path tokenizerPath) throws Exception {
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    }

    public float score(String query, String document) throws Exception {
        Encoding encoding = tokenizer.encode(query, document, true, true);
        long[] ids = truncate(encoding.getIds());
        long[] mask = truncate(encoding.getAttentionMask());
        long[] typeIds = truncate(encoding.getTypeIds());
        long[] shape = {1L, ids.length};
        try (var inputIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
             var attentionMask = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
             var tokenTypeIds = OnnxTensor.createTensor(env, LongBuffer.wrap(typeIds), shape);
             var result = session.run(Map.of("input_ids", inputIds, "attention_mask", attentionMask, "token_type_ids", tokenTypeIds))) {
            float[][] logits = (float[][]) result.get(0).getValue();
            return logits[0].length == 1 ? logits[0][0] : sigmoid(logits[0][1] - logits[0][0]);
        }
    }

    public List<String> rerank(String query, List<String> documents, int topN) throws Exception {
        float[] scores = new float[documents.size()];
        for (int i = 0; i < documents.size(); i++) scores[i] = score(query, documents.get(i));
        return IntStream.range(0, documents.size()).boxed()
            .sorted(Comparator.comparingDouble((Integer i) -> scores[i]).reversed())
            .limit(topN)
            .map(documents::get)
            .toList();
    }

    private static long[] truncate(long[] values) {
        return values.length <= MAX_TOKENS ? values : Arrays.copyOf(values, MAX_TOKENS);
    }

    private static float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    @Override
    public void close() throws Exception {
        session.close();
    }
}
