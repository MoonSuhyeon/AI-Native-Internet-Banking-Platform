package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.domain.TrackDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Shadow Mode 서비스 — phase-b-operational.md §B3.
 *
 * <p>{@code ai.shadow.enabled=true} 일 때만 빈 등록.
 * 프로덕션 파이프라인 완료 후 {@code @Async("shadowExecutor")} 로 비동기 실행.
 * shadow 실패는 어떠한 경우에도 prod 결과를 변경하지 않는다.
 *
 * <p>sampling-rate < 1.0 이면 ThreadLocalRandom 으로 샘플링 (전건이 아닌 일부만 shadow).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.shadow", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ShadowRunProperties.class)
public class ShadowModeService {

    private final PreReviewAgentService agentService;
    private final ShadowComparisonEvaluator evaluator;
    private final ShadowResultRepository repository;
    private final ShadowRunProperties props;
    private final AgentMetricsRecorder metricsRecorder;

    /**
     * Shadow run 비동기 실행.
     *
     * @param revId      심사 ID
     * @param request    자동심사 요청
     * @param decision   RuleEngine 결과 (prod 와 동일)
     * @param prodOpinion 프로덕션 에이전트 의견
     */
    @Async("shadowExecutor")
    public void runShadow(Long revId,
                          AutoReviewRequest request,
                          TrackDecision decision,
                          AgentOpinion prodOpinion) {
        if (!shouldSample()) {
            log.debug("[Shadow] revId={} sampling skip (rate={})", revId, props.samplingRate());
            return;
        }

        log.info("[Shadow] revId={} shadow run start", revId);
        try {
            AgentOpinion shadowOpinion = agentService.run(revId, request, decision);

            ShadowComparisonResult result = evaluator.evaluate(
                    revId, prodOpinion, shadowOpinion,
                    decision.track(),
                    props.model(), props.promptVersion(),
                    props.ragEnabled(), props.ragBackend());

            repository.insert(result);

            if (result.diverged()) {
                metricsRecorder.recordDisagreement(decision.track());
                log.warn("[Shadow] revId={} diverged reasons={}", revId, result.divergeReasons());
            }

            log.info("[Shadow] revId={} done diverged={}", revId, result.diverged());
        } catch (Exception e) {
            // shadow 실패 → prod 영향 없음, 로그만
            log.error("[Shadow] revId={} shadow run 실패 — prod 영향 없음", revId, e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private boolean shouldSample() {
        double rate = props.samplingRate();
        return rate >= 1.0 || ThreadLocalRandom.current().nextDouble() < rate;
    }
}
