package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.rule.domain.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Prod vs. Shadow AgentOpinion 비교기 — phase-b-operational.md §B3.
 *
 * <p>발산(diverge) 판단 기준 4가지 (D4-2: ragEnabled=true 시 #4 활성):
 * <ol>
 *   <li>riskLevel 불일치 → {@code RISK_LEVEL_MISMATCH}</li>
 *   <li>|decisionScore 차| > threshold → {@code DECISION_SCORE_GAP}</li>
 *   <li>disagreement 플래그 불일치 → {@code DISAGREEMENT_MISMATCH}</li>
 *   <li>|policyFlags 수 차| >= citationDiffThreshold → {@code POLICY_FLAG_DIFF} (RAG 컨텍스트 변화 감지)</li>
 * </ol>
 * fallback 의견(fallbackReason != null)은 비교 제외 — 정상 실행 결과끼리만 비교.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowComparisonEvaluator {

    private final ShadowRunProperties props;

    /**
     * @param revId               심사 ID (로그용)
     * @param prod                프로덕션 의견
     * @param shadow              shadow 의견
     * @param track               공통 트랙
     * @param shadowModel         shadow 모델명
     * @param shadowPromptVersion shadow 프롬프트 버전
     * @param ragEnabled          shadow run 이 RAG 컨텍스트를 사용했는지 여부
     * @param ragBackend          shadow run 이 사용한 RAG 백엔드 (inline / es)
     * @return 비교 결과
     */
    public ShadowComparisonResult evaluate(
            Long revId,
            AgentOpinion prod,
            AgentOpinion shadow,
            Track track,
            String shadowModel,
            String shadowPromptVersion,
            boolean ragEnabled,
            String ragBackend
    ) {
        List<String> reasons = new ArrayList<>();

        // fallback 의견은 비교 불가 → not diverged (측정 보류)
        if (prod.fallbackReason() != null || shadow.fallbackReason() != null) {
            log.debug("[Shadow] revId={} 비교 생략 — fallback prod={} shadow={}",
                    revId, prod.fallbackReason(), shadow.fallbackReason());
            return new ShadowComparisonResult(
                    revId, prod, shadow, false, List.of(), track, shadowModel, shadowPromptVersion, ragEnabled, ragBackend);
        }

        // 1. riskLevel 불일치
        if (prod.riskLevel() != null && shadow.riskLevel() != null
                && prod.riskLevel() != shadow.riskLevel()) {
            reasons.add("RISK_LEVEL_MISMATCH");
        }

        // 2. decisionScore 차이
        if (prod.decisionScore() != null && shadow.decisionScore() != null) {
            double gap = Math.abs(prod.decisionScore() - shadow.decisionScore());
            if (gap > props.divergeScoreThreshold()) {
                reasons.add("DECISION_SCORE_GAP");
            }
        }

        // 3. disagreement 불일치
        if (prod.disagreement() != shadow.disagreement()) {
            reasons.add("DISAGREEMENT_MISMATCH");
        }

        // 4. policyFlags 수 차이 — RAG 컨텍스트 변화 감지 (ragEnabled 시에만)
        if (ragEnabled) {
            int flagDiff = Math.abs(prod.policyFlags().size() - shadow.policyFlags().size());
            if (flagDiff >= props.citationDiffThreshold()) {
                reasons.add("POLICY_FLAG_DIFF");
            }
        }

        boolean diverged = !reasons.isEmpty();
        if (diverged) {
            log.warn("[Shadow] revId={} DIVERGED reasons={}", revId, reasons);
        } else {
            log.debug("[Shadow] revId={} consistent", revId);
        }

        return new ShadowComparisonResult(
                revId, prod, shadow, diverged, reasons, track, shadowModel, shadowPromptVersion, ragEnabled, ragBackend);
    }
}
