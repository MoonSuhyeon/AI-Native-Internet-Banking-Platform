package com.bank.loan.advisory.rag;

/**
 * 텍스트 임베딩 클라이언트 추상화. 구현체 교체(자체 호스팅 모델 / 외부 API)는
 * 이 인터페이스의 다른 빈을 등록하면 된다.
 *
 * 현재 유일한 구현체: {@link StubEmbeddingClient} (결정론적 단위벡터, 테스트/개발 전용).
 * 운영 투입 시 BGE-M3, SBERT-Ko 등 자체 호스팅 모델 어댑터로 교체.
 */
public interface EmbeddingClient {

    /** 이 클라이언트가 사용하는 CODE_MASTER EMBEDDING_MODEL 코드. */
    String defaultModelCd();

    /** 벡터 차원수. 현재 1536 (OpenAI text-embedding-3-small 기준 — 모델 변경 시 V4 마이그레이션). */
    int dimension();

    /**
     * 텍스트를 임베딩 벡터로 변환한다.
     *
     * @param text 원문 텍스트 (PII 마스킹 후 전달할 것 — {@link PiiMaskingUtil#mask})
     * @return 정규화된 float 배열 (L2 norm = 1.0)
     */
    float[] embed(String text);

    /** float[] → pgvector 리터럴 문자열 "[v0,v1,…,vn]". */
    static String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
