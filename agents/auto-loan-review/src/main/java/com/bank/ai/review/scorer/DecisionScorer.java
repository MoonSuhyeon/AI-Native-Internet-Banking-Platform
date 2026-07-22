package com.bank.ai.review.scorer;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;

/**
 * 자동심사 점수 산출 전략 인터페이스.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@link HeuristicDecisionScorer} — 인프로세스 휴리스틱 (기본, inference-server 불필요)</li>
 *   <li>{@link InferenceDecisionScorer} — inference-server HTTP 호출 (모델 아티팩트 배포 시)</li>
 * </ul>
 *
 * <p>선택: {@code ai.scoring.engine=heuristic|inference} (기본 {@code heuristic}).
 */
public interface DecisionScorer {

    /**
     * 대출 심사 입력을 받아 결정·확률·PD 스코어를 반환한다.
     *
     * @param req 심사 입력 (피처 전체)
     * @return 결정 결과 ({@link AutoReviewResponse})
     */
    AutoReviewResponse score(AutoReviewRequest req);
}
