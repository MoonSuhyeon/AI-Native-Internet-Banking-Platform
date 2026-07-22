package com.bank.ai.agent.guard;

import com.bank.ai.agent.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * reasoning_summary 톤이 RiskLevel 결정과 의미적으로 반대일 때 불일치 감지.
 *
 * <p>pre-review-agent-plan.md §가드레일 — disagreement=true → UI 빨간 배지.
 * 강제 게이트 없음 — 에이전트 분석은 계속 진행하고 UI 에서 표시만.
 *
 * <p>키워드 기반 단순 감지 (A10 이후 정밀화 예정). 양성/음성 시그널 모두 존재하면
 * 중립 판정 (disagreement=false) — 오탐 최소화 우선.
 */
@Slf4j
@Component
public class SemanticDisagreementDetector {

    private static final Pattern NEGATIVE_SIGNAL = Pattern.compile(
            "위험|부담|초과|미충족|불가|우려|경고|반려|거절|높[은음]|심각|문제|경보|임계",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern POSITIVE_SIGNAL = Pattern.compile(
            "안전|양호|우수|강점|승인|적합|정상|낮[은음]|원활|안정|이하|통과",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * @param riskLevel        에이전트가 파생한 리스크 수준
     * @param reasoningSummary LLM 생성 또는 템플릿 요약 문장
     * @return true 이면 불일치 — AgentOpinion.disagreement=true 로 반영
     */
    public boolean detect(RiskLevel riskLevel, String reasoningSummary) {
        if (reasoningSummary == null || reasoningSummary.isBlank()) {
            return false;
        }

        boolean hasNegative = NEGATIVE_SIGNAL.matcher(reasoningSummary).find();
        boolean hasPositive = POSITIVE_SIGNAL.matcher(reasoningSummary).find();

        // 양쪽 시그널이 모두 있으면 혼재 → 중립 판정
        if (hasNegative && hasPositive) {
            return false;
        }

        boolean disagreement = switch (riskLevel) {
            case LOW  -> hasNegative; // 승인 권고인데 부정적 요약
            case HIGH -> hasPositive; // 거절 권고인데 긍정적 요약
            case MEDIUM -> false;     // 회색지대 — A10 이후 정밀화
        };

        if (disagreement) {
            log.warn("SemanticDisagreementDetector: disagreement 감지 riskLevel={} summary={}",
                    riskLevel, reasoningSummary);
        }
        return disagreement;
    }
}
