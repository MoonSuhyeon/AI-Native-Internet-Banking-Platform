package com.bank.ai.rag.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StubEmbeddingClientTest {

    private final StubEmbeddingClient client = new StubEmbeddingClient();

    @Test
    void 동일_텍스트는_동일_벡터() {
        float[] v1 = client.embed("주담대 DSR 한도");
        float[] v2 = client.embed("주담대 DSR 한도");
        assertThat(v1).isEqualTo(v2);
    }

    @Test
    void 다른_텍스트는_다른_벡터() {
        float[] v1 = client.embed("주담대 DSR 한도");
        float[] v2 = client.embed("신용점수 최소 기준");
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void 벡터_차원은_768() {
        float[] vec = client.embed("test");
        assertThat(vec).hasSize(768);
    }

    @Test
    void 벡터_L2_노름은_1에_가까움() {
        float[] vec = client.embed("정규화 검증");
        double norm = 0.0;
        for (float v : vec) norm += (double) v * v;
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void embedAll_배치는_단건과_동일한_결과() {
        List<String> texts = List.of("텍스트A", "텍스트B");
        List<float[]> batch = client.embedAll(texts);
        assertThat(batch).hasSize(2);
        assertThat(batch.get(0)).isEqualTo(client.embed("텍스트A"));
        assertThat(batch.get(1)).isEqualTo(client.embed("텍스트B"));
    }
}
