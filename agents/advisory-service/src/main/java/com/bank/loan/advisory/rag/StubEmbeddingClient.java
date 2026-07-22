package com.bank.loan.advisory.rag;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Random;

/**
 * 결정론적 단위벡터를 생성하는 스텁 임베딩 클라이언트.
 *
 * test 프로파일 전용 — 동일 텍스트는 항상 동일 벡터(코사인 1.0) 보장.
 * 운영에서는 AdvisoryOpenAiEmbeddingClient 가 로드됨(@Profile("!test")).
 */
@Component
@Profile("test")
public class StubEmbeddingClient implements EmbeddingClient {

    private static final String MODEL_CD  = "SBERT_KO_V1";
    private static final int    DIMENSION = 1536;

    @Override
    public String defaultModelCd() {
        return MODEL_CD;
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public float[] embed(String text) {
        // text.hashCode() × 31 + length 로 시드 — 같은 문자열이면 항상 같은 결과
        long seed = (long) text.hashCode() * 31L + text.length();
        Random rng = new Random(seed);

        float[] v = new float[DIMENSION];
        float norm = 0f;
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = rng.nextFloat() * 2f - 1f;   // [-1, 1) 균등 분포
            norm += v[i] * v[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSION; i++) {
            v[i] /= norm;                         // L2 정규화
        }
        return v;
    }
}
