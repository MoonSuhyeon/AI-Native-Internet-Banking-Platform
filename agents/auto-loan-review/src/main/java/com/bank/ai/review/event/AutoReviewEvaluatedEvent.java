package com.bank.ai.review.event;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.rule.domain.TrackDecision;

/**
 * 자동심사 동기 평가 완료 이벤트 — plan/llm-pipeline.md §7.1.
 * 비동기 LLM 리포트 생성을 트리거한다.
 */
public record AutoReviewEvaluatedEvent(
        Long revId,
        AutoReviewRequest request,
        AutoReviewResponse inference,
        TrackDecision decision
) {
}
