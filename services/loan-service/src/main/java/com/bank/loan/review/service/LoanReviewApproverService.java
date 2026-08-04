package com.bank.loan.review.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.AdvisoryClient;
import com.bank.loan.advisory.AdvisoryReportSummary;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.notification.event.LoanApprovedEvent;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.dto.ApproverApproveRequest;
import com.bank.loan.review.dto.LoanReviewResponse;
import com.bank.loan.review.metrics.ReviewAdoptionMetrics;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LoanReviewApproverService {

    private static final String DOMAIN_CD               = "LOAN";
    private static final String TARGET_REVIEW           = "LOAN_REVIEW";
    private static final String TARGET_APPLICATION      = "LOAN_APPLICATION";
    private static final String REASON_APPROVER_APPROVED = "APPROVER_APPROVED";
    private static final String REASON_APPROVER_REJECTED = "APPROVER_REJECTED";

    private final LoanReviewRepository reviewRepository;
    private final LoanApplicationRepository applicationRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final ApplicationEventPublisher eventPublisher;
    private final AdvisoryClient advisoryClient;
    private final ReviewAdoptionMetrics adoptionMetrics;

    /**
     * 승인자 최종 확정.
     * 사전조건: PENDING_APPROVER + 4-eye(approverId ≠ reviewerId).
     * APPROVE_AS_IS / OVERRIDE_APPROVED / OVERRIDE_REJECTED 세 가지 결정.
     * 완료 시 신청 상태 PRESCREENED → APPROVED/REJECTED 전이.
     */
    @Transactional
    public LoanReviewResponse approverApprove(Long applId, ApproverApproveRequest req) {
        LoanApplication application = applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        LoanReview review = reviewRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_042));

        if (!review.isPendingApprover()) {
            throw new BusinessException(LoanErrorCode.LOAN_195);
        }

        // 4-eye 판정은 인증된 호출자(currentActorId)로만 한다. 요청 바디 값은 신뢰하지 않는다.
        Long approverId = currentActor.currentActorId();
        if (approverId == null || CurrentActorProvider.SYSTEM.equals(approverId)) {
            throw new BusinessException(LoanErrorCode.LOAN_196);
        }
        if (approverId.equals(review.getReviewerId())) {
            throw new BusinessException(LoanErrorCode.LOAN_196);
        }

        assertNoCriticalUnackedAdvisory(review.getRevId());

        boolean isOverride = LoanReview.APPROVER_OVERRIDE_APPROVED.equals(req.approverDecisionCd())
                || LoanReview.APPROVER_OVERRIDE_REJECTED.equals(req.approverDecisionCd());
        if (isOverride && (req.overrideReasonCd() == null || req.overrideReasonCd().isBlank())) {
            throw new BusinessException(LoanErrorCode.LOAN_197);
        }
        if (LoanReview.APPROVER_OVERRIDE_APPROVED.equals(req.approverDecisionCd())) {
            if (req.overrideAmount() == null || req.overrideRateBps() == null || req.overridePeriodMo() == null) {
                throw new BusinessException(LoanErrorCode.LOAN_198);
            }
        }

        OffsetDateTime now = OffsetDateTime.now();
        String prevApplStatus = application.currentStatus();

        review.approverApprove(
                approverId,
                req.approverDecisionCd(),
                req.overrideReasonCd(),
                req.overrideRemark(),
                req.overrideAmount(),
                req.overrideRateBps(),
                req.overridePeriodMo(),
                req.overrideRejectReasonCd(),
                now
        );

        boolean finalApproved = LoanReview.DECISION_APPROVED.equals(review.getRevDecisionCd());
        if (finalApproved) {
            application.markApproved();
        } else {
            application.markRejected();
        }

        // 심사가 최종 확정된 시점 — AI 권고 채택 여부와 4-eye 판정을 계측한다.
        adoptionMetrics.recordFinalDecision(review, req.approverDecisionCd());

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_REVIEW, review.getRevId(),
                LoanReview.STATUS_PENDING_APPROVER, LoanReview.STATUS_COMPLETED,
                req.approverDecisionCd(),
                req.overrideRemark(),
                currentActor.currentActorId()
        ));

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_APPLICATION, applId,
                prevApplStatus, application.currentStatus(),
                finalApproved ? REASON_APPROVER_APPROVED : REASON_APPROVER_REJECTED,
                null,
                currentActor.currentActorId()
        ));

        if (finalApproved) {
            eventPublisher.publishEvent(new LoanApprovedEvent(
                    applId, review.getRevId(), application.getCustomerId(), review.getApprovedAmount()
            ));
        }

        return LoanReviewResponse.of(review);
    }

    private void assertNoCriticalUnackedAdvisory(Long revId) {
        List<AdvisoryReportSummary> reports = advisoryClient.getReports(revId);
        boolean hasUnackedCritical = reports.stream()
                .filter(r -> "CRITICAL".equals(r.severityCd()))
                .anyMatch(r -> !"ACKED".equals(r.advrStatusCd())
                        && !"RESOLVED".equals(r.advrStatusCd()));
        if (hasUnackedCritical) {
            throw new BusinessException(LoanErrorCode.LOAN_201);
        }
    }

    @Transactional(readOnly = true)
    public List<LoanReviewResponse> listPendingApprover() {
        return reviewRepository
                .findByRevStatusCdAndDeletedAtIsNullOrderByReviewedAtAsc(LoanReview.STATUS_PENDING_APPROVER)
                .stream()
                .map(LoanReviewResponse::of)
                .toList();
    }
}
