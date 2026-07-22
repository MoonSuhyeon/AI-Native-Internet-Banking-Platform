package com.bank.ai.review.service;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.scorer.DecisionScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 자동심사 단건 추론 진입점.
 *
 * <p>점수 산출은 {@link DecisionScorer} 구현체에 위임한다:
 * <ul>
 *   <li>{@code ai.scoring.engine=heuristic} (기본) — 인프로세스 휴리스틱, inference-server 불필요</li>
 *   <li>{@code ai.scoring.engine=inference} — inference-server HTTP 호출 (모델 배포 환경)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoReviewService {

    private final DecisionScorer decisionScorer;

    public AutoReviewResponse review(AutoReviewRequest req) {
        return decisionScorer.score(req);
    }
}
