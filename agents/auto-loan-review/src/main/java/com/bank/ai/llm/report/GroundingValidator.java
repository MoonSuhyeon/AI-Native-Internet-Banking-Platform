package com.bank.ai.llm.report;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.policy.RagPolicyIndex;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ReviewReport grounding 검증 — plan/llm-pipeline.md §5.4, phase-d-rag.md D2-6.
 *
 * <p>LLM 이 산출한 리포트의 citation·riskFactor·strength 가 모두 실제 정책 id 를 참조하는지 검사.
 * Track 2 는 법령·정책 인용 ≥ 2 강제 (감독·소송 대비).
 *
 * <p>D2-6: Citation.id prefix 분기 —
 * {@code rag:} prefix → {@link RagPolicyIndex} (ai.rag.enabled=true 시에만 활성),
 * {@code inline:} prefix 또는 prefix 없음 → {@link InlinePolicyIndex}.
 *
 * <p>실패 시 호출 측이 {@code TemplateFallback} 로 우회 — LLM 환각 인용 차단.
 *
 * <p>A5 확장: {@link #validateNumericClaims} 로 에이전트 의견의 수치 클레임과
 * TrackDecision 기준값 정합성 검증.
 */
@Slf4j
@Component
public class GroundingValidator {

    static final String INLINE_PREFIX = "inline:";
    static final String RAG_PREFIX = "rag:";

    /** Track 2 (자동 반려) 가 가져야 할 최소 인용 수. */
    public static final int MIN_CITATIONS_TRACK_2 = 2;

    private final InlinePolicyIndex inlinePolicyIndex;

    @Nullable
    private final RagPolicyIndex ragPolicyIndex;

    private final AgentMetricsRecorder metricsRecorder;

    @Autowired
    public GroundingValidator(InlinePolicyIndex inlinePolicyIndex,
                               Optional<RagPolicyIndex> ragPolicyIndex,
                               AgentMetricsRecorder metricsRecorder) {
        this.inlinePolicyIndex = inlinePolicyIndex;
        this.ragPolicyIndex = ragPolicyIndex.orElse(null);
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * @return 첫 위반 사유 (없으면 빈 Optional). 다중 위반도 첫 1건만 — fallback 단계 reasoning 용.
     */
    public ValidationResult validate(ReviewReport report) {
        List<String> issues = new ArrayList<>();

        if (report.track() == Track.TRACK_2
                && report.citations().size() < MIN_CITATIONS_TRACK_2) {
            issues.add("Track 2 인용 부족 (%d < %d)"
                    .formatted(report.citations().size(), MIN_CITATIONS_TRACK_2));
        }

        for (var c : report.citations()) {
            if (!resolveExists(c.id())) {
                issues.add("citation id '%s' 미존재".formatted(c.id()));
            }
        }
        for (var r : report.riskFactors()) {
            if (r.citationId() != null && !resolveExists(r.citationId())) {
                issues.add("riskFactor[%s] citationId '%s' 미존재"
                        .formatted(r.code(), r.citationId()));
            }
        }
        for (var s : report.strengths()) {
            if (s.citationId() != null && !resolveExists(s.citationId())) {
                issues.add("strength[%s] citationId '%s' 미존재"
                        .formatted(s.code(), s.citationId()));
            }
        }

        long ragCitationCount = report.citations().stream()
                .filter(c -> c.id().startsWith(RAG_PREFIX))
                .count();
        metricsRecorder.recordRagCitationCount(report.track(), (int) ragCitationCount);

        if (issues.isEmpty()) {
            return ValidationResult.ok();
        }
        log.warn("grounding 검증 실패 track={} issues={}", report.track(), issues);
        return ValidationResult.fail(issues);
    }

    /**
     * prefix 기반 PolicyIndex 라우팅.
     * {@code rag:} → RagPolicyIndex (비활성이면 false),
     * {@code inline:} 또는 prefix 없음 → InlinePolicyIndex.
     */
    private boolean resolveExists(String id) {
        if (id.startsWith(RAG_PREFIX)) {
            if (ragPolicyIndex == null) {
                log.warn("rag: citation 검증 시도이나 RagPolicyIndex 비활성: id={}", id);
                return false;
            }
            return ragPolicyIndex.exists(id.substring(RAG_PREFIX.length()));
        }
        String lookupId = id.startsWith(INLINE_PREFIX) ? id.substring(INLINE_PREFIX.length()) : id;
        return inlinePolicyIndex.exists(lookupId);
    }

    /**
     * AgentOpinion 수치 클레임과 TrackDecision 기준값 정합성 검증 — A5 신규.
     *
     * <p>검사 항목:
     * <ol>
     *   <li>decisionScore / pdScore 범위 [0, 1]</li>
     *   <li>opinion 수치가 TrackDecision 참조값에서 허용 오차({@value #SCORE_TOLERANCE}) 이내</li>
     *   <li>시뮬레이션 결과 점수 범위 [0, 1]</li>
     * </ol>
     *
     * <p>실패 시 호출 측이 {@code AgentOpinion.fallback(GROUNDING_FAILED)} 로 우회.
     *
     * @param opinion  에이전트 의견 (분석 완료 후)
     * @param decision RuleEngine 트랙 분기 결과 (기준값 참조용)
     */
    public ValidationResult validateNumericClaims(AgentOpinion opinion, TrackDecision decision) {
        if (opinion == null || opinion.fallbackReason() != null) {
            return ValidationResult.ok(); // fallback 의견은 수치 검증 대상 아님
        }

        List<String> issues = new ArrayList<>();

        // 1. 수치 범위 검사 [0, 1]
        if (opinion.decisionScore() != null
                && (opinion.decisionScore() < 0.0 || opinion.decisionScore() > 1.0)) {
            issues.add("decisionScore 범위 초과: %.6f".formatted(opinion.decisionScore()));
        }
        if (opinion.pdScore() != null
                && (opinion.pdScore() < 0.0 || opinion.pdScore() > 1.0)) {
            issues.add("pdScore 범위 초과: %.6f".formatted(opinion.pdScore()));
        }

        // 2. TrackDecision 참조값 드리프트 검사
        if (opinion.decisionScore() != null && decision.decisionScore() != null) {
            double delta = Math.abs(opinion.decisionScore() - decision.decisionScore());
            if (delta > SCORE_TOLERANCE) {
                issues.add("decisionScore 드리프트: opinion=%.6f decision=%.6f"
                        .formatted(opinion.decisionScore(), decision.decisionScore()));
            }
        }
        if (opinion.pdScore() != null) {
            double delta = Math.abs(opinion.pdScore() - decision.pd());
            if (delta > SCORE_TOLERANCE) {
                issues.add("pdScore 드리프트: opinion=%.6f decision=%.6f"
                        .formatted(opinion.pdScore(), decision.pd()));
            }
        }

        // 3. 시뮬레이션 결과 범위 검사
        for (var sim : opinion.simulationResults()) {
            if (sim.newDecisionScore() < 0.0 || sim.newDecisionScore() > 1.0) {
                issues.add("simulation[%s] decisionScore 범위 초과: %.6f"
                        .formatted(sim.scenario(), sim.newDecisionScore()));
            }
            if (sim.newPdScore() < 0.0 || sim.newPdScore() > 1.0) {
                issues.add("simulation[%s] pdScore 범위 초과: %.6f"
                        .formatted(sim.scenario(), sim.newPdScore()));
            }
        }

        if (issues.isEmpty()) {
            return ValidationResult.ok();
        }
        log.warn("numeric claims 검증 실패: {}", issues);
        return ValidationResult.fail(issues);
    }

    /** 수치 드리프트 허용 오차 — 부동소수점 직접 복사 기준으로 극소 허용. */
    private static final double SCORE_TOLERANCE = 1e-9;

    public record ValidationResult(boolean passed, List<String> issues) {

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult fail(List<String> issues) {
            return new ValidationResult(false, List.copyOf(issues));
        }
    }
}
