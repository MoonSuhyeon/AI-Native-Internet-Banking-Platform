package com.bank.loan.security;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.AdvisoryClient;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.dto.ApproverApproveRequest;
import com.bank.loan.review.metrics.ReviewAdoptionMetrics;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.review.service.LoanReviewApproverService;
import com.bank.loan.support.LoanErrorCode;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 4-eye 원칙 단위 테스트.
 * 통합 테스트에서는 bias-check disabled 환경으로 PENDING_APPROVER 상태에 도달하기 어려워
 * Mockito 로 서비스 레이어 직접 검증한다.
 *
 * 테스트별로 분기 지점이 달라 @BeforeEach 공유 스텁이 일부 경로에서만 쓰이므로 LENIENT 로 둔다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanReview4EyeUnitTest {

    private static final Long APPL_ID     = 1L;
    private static final Long REVIEWER_ID = 20L;
    private static final Long APPROVER_ID = 30L;

    @Mock private LoanReviewRepository reviewRepository;
    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private StatusHistoryPublisher statusHistoryPublisher;
    @Mock private CurrentActorProvider currentActorProvider;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AdvisoryClient advisoryClient;
    @Mock private ReviewAdoptionMetrics adoptionMetrics;

    private LoanReviewApproverService service;

    private LoanApplication application;
    private LoanReview pendingApproverReview;

    @BeforeEach
    void setUp() {
        service = new LoanReviewApproverService(
                reviewRepository, applicationRepository,
                statusHistoryPublisher, currentActorProvider,
                eventPublisher, advisoryClient, adoptionMetrics
        );

        application = LoanApplication.builder()
                .applId(APPL_ID).applNo("AP-4EYE").customerId(100L).prodId(1L)
                .channelCd("MOBILE").requestedAmount(30_000_000L).requestedPeriodMo(36)
                .repaymentMethodCd("EQUAL").applStatusCd("PRESCREENED")
                .appliedAt(OffsetDateTime.now())
                .build();

        pendingApproverReview = LoanReview.builder()
                .applId(APPL_ID).revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_PENDING_APPROVER)
                .revDecisionCd(LoanReview.DECISION_APPROVED)
                .approvedAmount(30_000_000L).approvedRateBps(500).approvedPeriodMo(36)
                .reviewerId(REVIEWER_ID)
                .reviewedAt(OffsetDateTime.now())
                .build();

        when(applicationRepository.findByApplIdAndDeletedAtIsNull(APPL_ID))
                .thenReturn(Optional.of(application));
        when(reviewRepository.findByApplIdAndDeletedAtIsNull(APPL_ID))
                .thenReturn(Optional.of(pendingApproverReview));
        when(advisoryClient.getReports(any())).thenReturn(List.of());
        // 4-eye 는 인증 주체(currentActorId) 기준 — 기본은 심사원과 다른 승인자로 통과
        when(currentActorProvider.currentActorId()).thenReturn(APPROVER_ID);
    }

    @Test
    void 심사자와_동일한_승인자_LOAN_196() {
        // 인증된 호출자가 곧 심사원 본인 → 4-eye 위반
        when(currentActorProvider.currentActorId()).thenReturn(REVIEWER_ID);
        ApproverApproveRequest req = new ApproverApproveRequest(
                LoanReview.APPROVER_AS_IS,
                null, null, null, null, null, null
        );

        assertThatThrownBy(() -> service.approverApprove(APPL_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_196);
    }

    @Test
    void 다른_승인자_4eye_통과() {
        // currentActorId(APPROVER_ID) != reviewerId → 통과
        ApproverApproveRequest req = new ApproverApproveRequest(
                LoanReview.APPROVER_AS_IS,
                null, null, null, null, null, null
        );

        // LOAN_196 이 발생하지 않으면 4-eye 통과. 이후 로직은 save mock 필요.
        when(reviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        // BusinessException 발생 시 LOAN_196 이 아닌 다른 코드여야 함을 검증
        try {
            service.approverApprove(APPL_ID, req);
        } catch (BusinessException e) {
            assertThatThrownBy(() -> { throw e; })
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isNotEqualTo(LoanErrorCode.LOAN_196);
        }
    }

    @Test
    void PENDING_APPROVER_아닌_상태_LOAN_195() {
        LoanReview completedReview = LoanReview.builder()
                .applId(APPL_ID).revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .reviewerId(REVIEWER_ID)
                .reviewedAt(OffsetDateTime.now())
                .build();

        when(reviewRepository.findByApplIdAndDeletedAtIsNull(APPL_ID))
                .thenReturn(Optional.of(completedReview));

        ApproverApproveRequest req = new ApproverApproveRequest(
                LoanReview.APPROVER_AS_IS,
                null, null, null, null, null, null
        );

        assertThatThrownBy(() -> service.approverApprove(APPL_ID, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_195);
    }
}
