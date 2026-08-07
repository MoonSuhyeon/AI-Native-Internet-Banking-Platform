package com.bank.docagent.review.service;

import com.bank.docagent.retention.RetentionService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import com.bank.docagent.submission.domain.DocumentSubmission.VerifyStatus;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import com.bank.docagent.observability.DocAgentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 심사원 HOLD 검토 서비스.
 * 자동 UNLOCK 경로 없음 — 반드시 사람이 호출하는 엔드포인트를 통해서만 결정.
 * 결정 후: 보존 기간 재계산 + CONFIRMED_FORGERY 시 감사팀 이관 이벤트 발행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HumanReviewService {

    private final DocumentSubmissionRepository submissionRepository;
    private final RetentionService             retentionService;
    private final DocAgentMetrics              metrics;

    @Transactional
    public DocumentSubmission decide(UUID submissionId, HumanReviewStatus decision,
                                     String reviewerId) {
        DocumentSubmission submission = submissionRepository.findById(submissionId)
            .orElseThrow(() -> new IllegalArgumentException("제출 건 없음: " + submissionId));

        if (submission.getHumanReviewStatus() != HumanReviewStatus.PENDING) {
            throw new IllegalStateException(
                "PENDING 상태가 아닌 건은 재결정 불가: " + submission.getHumanReviewStatus());
        }
        if (decision == HumanReviewStatus.NOT_REQUIRED) {
            throw new IllegalArgumentException("NOT_REQUIRED는 심사원 결정값이 아닙니다");
        }
        // 컨트롤러에도 @NotBlank 가 있지만 여기서 한 번 더 막는다.
        // 위조 확정은 사람이 책임지는 행위라, 심사원 없는 결정이 남으면 감사 추적이 끊긴다.
        // HTTP 밖(배치·이벤트 소비자)에서 부르는 경로가 생기면 컨트롤러 검증은 우회된다.
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("심사원 ID 없이 결정할 수 없습니다");
        }

        submission.applyHumanDecision(decision, reviewerId);

        // 결정 후 보존 기간 재계산 (LOCKED=10년, CLEARED=5년)
        retentionService.applyRetention(submission, submission.getVerifyStatus());

        // 자동 판정(HOLD)을 사람이 어떻게 결론지었는가. 이 서비스의 채택률 축이다.
        // CONFIRMED_FORGERY = 자동 판정 확인, CLEARED = 뒤집음.
        metrics.recordHumanDecision(decision.name());

        log.info("심사원 결정 완료: submissionId={} decision={} reviewerId={}",
            submissionId, decision, reviewerId);
        return submission;
    }
}
