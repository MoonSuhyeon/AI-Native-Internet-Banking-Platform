package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.rule.domain.Track;

import java.util.List;

/**
 * Shadow vs. Prod 비교 결과 VO — {@link ShadowComparisonEvaluator} 반환값.
 *
 * @param revId               loan_review PK
 * @param prodOpinion         프로덕션 에이전트 의견
 * @param shadowOpinion       shadow 에이전트 의견
 * @param diverged            두 의견이 발산(diverge)했는지 여부
 * @param divergeReasons      발산 사유 코드 목록 (예: RISK_LEVEL_MISMATCH, POLICY_FLAG_DIFF)
 * @param track               공통 트랙 (RuleEngine 결과, prod/shadow 동일)
 * @param shadowModel         shadow 에이전트에 사용된 모델명
 * @param shadowPromptVersion shadow 에이전트에 사용된 프롬프트 버전
 * @param ragEnabled          shadow run 이 RAG 컨텍스트를 사용했는지 여부 (D4-2)
 * @param ragBackend          shadow run 이 사용한 RAG 백엔드 (inline / es) (E4-2)
 */
public record ShadowComparisonResult(
        Long revId,
        AgentOpinion prodOpinion,
        AgentOpinion shadowOpinion,
        boolean diverged,
        List<String> divergeReasons,
        Track track,
        String shadowModel,
        String shadowPromptVersion,
        boolean ragEnabled,
        String ragBackend
) {}
