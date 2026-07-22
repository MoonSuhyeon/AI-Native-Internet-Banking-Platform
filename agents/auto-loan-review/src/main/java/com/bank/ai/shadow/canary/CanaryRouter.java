package com.bank.ai.shadow.canary;

import com.bank.ai.metrics.AgentMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Canary 라우터 — 요청별 ES/inline 경로 선택 (E4-4).
 *
 * <p>{@code ai.canary.enabled=true} 시에만 빈 등록.
 * {@link com.bank.ai.rag.retrieval.RagRetrievalService} 가 optional 주입해 사용.
 *
 * <p>라우팅 로직: {@code ThreadLocalRandom.nextInt(100) < esWeight} 이면 ES 사용.
 * weight=0 → 항상 inline, weight=100 → 항상 ES.
 *
 * <p>단계별 운영 절차:
 * <ol>
 *   <li>shadow 100% → {@link com.bank.ai.shadow.ShadowModeService} 로 ES 결과 수집 (현재 단계)</li>
 *   <li>canary 5%  → {@code AI_CANARY_ENABLED=true AI_CANARY_ES_WEIGHT=5}</li>
 *   <li>canary 25% → {@code AI_CANARY_ES_WEIGHT=25} (48h 후 게이트 통과 시)</li>
 *   <li>full 100%  → {@code AI_CANARY_ES_WEIGHT=100} 또는 {@code AI_RAG_BACKEND=es} 고정</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.canary", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CanaryProperties.class)
public class CanaryRouter {

    private final CanaryProperties props;
    private final AgentMetricsRecorder metricsRecorder;

    /**
     * 이 요청을 ES 경로로 라우팅할지 결정.
     *
     * @return true → ES RAG 사용, false → inline(RAG 없음)
     */
    public boolean shouldUseEs() {
        int weight = props.esWeight();
        boolean useEs = weight >= 100 || ThreadLocalRandom.current().nextInt(100) < weight;
        String backend = useEs ? "es" : "inline";
        metricsRecorder.recordCanaryRouted(backend);
        log.debug("[Canary] routed to {} (weight={}%)", backend, weight);
        return useEs;
    }
}
