package com.bank.ai.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 품질 지표.
 *
 * <p><b>왜 필요한가.</b> 기존 지표({@code rag.chunk.count}, {@code rag.search.miss})는
 * "몇 개 찾았나" 만 말한다. 0건은 잡히지만 <b>10건을 찾았는데 전부 관련이 없는 경우</b>는
 * 정상으로 보인다. 그러면 LLM 이 엉뚱한 근거로 답을 만들고, 그 답은 자신 있게 틀린다.
 *
 * <p>백엔드 태그를 나누는 것도 여기서 고정한다. RAG 경로가 둘(inline, es)인데 지표가
 * 합쳐지면 어느 쪽이 나쁜지 알 수 없고, 하나를 고쳐도 효과가 묻힌다.
 */
class RagRelevanceMetricsTest {

    private MeterRegistry registry;
    private AgentMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new AgentMetricsRecorder(registry);
    }

    @Test
    @DisplayName("최고 점수를 분포로 남긴다 — 관련성의 근사값이다")
    void topScoreIsRecorded() {
        recorder.recordRagTopScore("loan-policy", "inline", 0.82);

        assertThat(registry.get("rag.search.top.score")
                .tags("corpus", "loan-policy", "backend", "inline")
                .summary().max())
                .isEqualTo(0.82);
    }

    @Test
    @DisplayName("백엔드별로 나뉜다 — 합치면 어느 쪽이 나쁜지 알 수 없다")
    void scoresAreSeparatedByBackend() {
        recorder.recordRagTopScore("loan-policy", "inline", 0.20);
        recorder.recordRagTopScore("loan-policy", "es", 0.90);

        assertThat(registry.get("rag.search.top.score")
                .tags("corpus", "loan-policy", "backend", "inline").summary().max())
                .isEqualTo(0.20);
        assertThat(registry.get("rag.search.top.score")
                .tags("corpus", "loan-policy", "backend", "es").summary().max())
                .isEqualTo(0.90);
    }

    @Test
    @DisplayName("관련 없음은 miss 와 따로 센다 — 원인이 다르다")
    void lowRelevanceIsCountedSeparately() {
        // 못 찾은 것(miss)은 색인·필터를 보고, 찾았는데 관련 없는 것은
        // 임베딩·질의를 본다. 합쳐 세면 어디를 손볼지 알 수 없다.
        recorder.recordRagLowRelevance("loan-policy", "es");

        assertThat(registry.get("rag.search.low.relevance.total")
                .tags("corpus", "loan-policy", "backend", "es").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("rag.search.miss.total").counter())
                .as("관련 없음을 miss 로 세면 '검색이 안 된다' 로 오진하게 된다")
                .isNull();
    }
}
