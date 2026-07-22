package com.bank.ai.rag.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 결정론 임베딩 클라이언트 — 테스트·로컬 전용.
 *
 * <p>텍스트 SHA-256 → 768차원 float 벡터 고정 매핑 (text-embedding-005 기본 출력 dim 일치).
 * 동일 텍스트는 항상 동일 벡터를 반환하므로 단위 테스트에서 결정론 보장.
 * {@code ai.rag.embedding.provider=stub} (기본값) 시 활성.
 */
@Component
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider",
        havingValue = "stub", matchIfMissing = true)
public class StubEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSIONS = 768;

    @Override
    public float[] embed(String text) {
        byte[] hash = sha256(text);
        float[] vec = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            // 해시 바이트를 반복하여 [-1, 1] 범위 float 생성
            vec[i] = (hash[i % hash.length] & 0xFF) / 127.5f - 1.0f;
        }
        return normalize(vec);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    private static float[] normalize(float[] vec) {
        double norm = 0.0;
        for (float v : vec) norm += (double) v * v;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) return vec;
        float[] result = new float[vec.length];
        for (int i = 0; i < vec.length; i++) result[i] = (float) (vec[i] / norm);
        return result;
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 JVM", e);
        }
    }
}
