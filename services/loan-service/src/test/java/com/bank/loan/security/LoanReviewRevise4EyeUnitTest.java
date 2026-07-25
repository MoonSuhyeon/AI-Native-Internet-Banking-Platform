package com.bank.loan.security;

import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditevaluation.repository.CreditEvaluationRepository;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.dto.ReviseReviewRequest;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.review.service.ApprovedAmountCalculator;
import com.bank.loan.review.service.LoanReviewCheckLogWriter;
import com.bank.loan.review.service.LoanReviewReviseService;
import com.bank.loan.support.LoanErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * 본심사 정정(revise) 4-eye 단위 테스트.
 * 정정 행위자는 요청 바디가 아닌 인증 주체(currentActorId)로만 식별되며,
 * 최종 승인자(approverId) 본인이 자신의 승인 건을 단독 정정하지 못한다(LOAN_207).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanReviewRevise4EyeUnitTest {

    private static final Long APPL_ID     = 1L;
    private static final Long REVIEWER_ID = 20L;
    private static final Long APPROVER_ID = 30L;
    private static final Long OTHER_ID    = 40L;

    @Mock private LoanReviewRepository reviewRepository;
    @Mock private LoanApplicationRepository applicationRepository;
    @Mock private LoanProductRepository productRepository;
    @Mock private CreditEvaluationRepository creditEvaluationRepository;
    @Mock private ApprovedAmountCalculator approvedAmountCalculator;
    @Mock private LoanReviewCheckLogWriter checkLogWriter;
    @Mock private StatusHistoryPublisher statusHistoryPublisher;
    @Mock private CurrentActorProvider currentActorProvider;

    private LoanReviewReviseService service;

    private LoanApplication application;
    private LoanReview completedReview;

    @BeforeEach
    void setUp() {
        service = new LoanReviewReviseService(
                reviewRepository, applicationRepository, productRepository,
                creditEvaluationRepository, approvedAmountCalculator,
                checkLogWriter, statusHistoryPublisher, currentActorProvider
        );

        application = LoanApplication.builder()
                .applId(APPL_ID).applNo("AP-REVISE").customerId(100L).prodId(1L)
                .channelCd("MOBILE").requestedAmount(30_000_000L).requestedPeriodMo(36)
                .repaymentMethodCd("EQUAL").applStatusCd(LoanApplication.STATUS_APPROVED)
                .appliedAt(OffsetDateTime.now())
                .build();

        completedReview = LoanReview.builder()
                .applId(APPL_ID).revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .revDecisionCd(LoanReview.DECISION_APPROVED)
                .approvedAmount(30_000_000L).approvedRateBps(500).approvedPeriodMo(36)
                .reviewerId(REVIEWER_ID)
                .approverId(APPROVER_ID)
                .reviewedAt(OffsetDateTime.now())
                .build();

        when(applicationRepository.findByApplIdAndDeletedAtIsNull(APPL_ID))
                .thenReturn(Optional.of(application));
        when(reviewRepository.findByApplIdAndDeletedAtIsNull(APPL_ID))
                .thenReturn(Optional.of(completedReview));
    }

    private ReviseReviewRequest approveRevise() {
        return new ReviseReviewRequest(
                LoanReview.DECISION_APPROVED,
                25_000_000L, 600, 36,
                null, "정정", "ERROR_CORRECTION"
        );
    }

    @Test
    void 최종승인자_본인이_단독_정정_LOAN_207() {
        when(currentActorProvider.currentActorId()).thenReturn(APPROVER_ID);

        assertThatThrownBy(() -> service.revise(APPL_ID, approveRevise()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_207);
    }

    @Test
    void 인증_주체_없음_LOAN_207() {
        when(currentActorProvider.currentActorId()).thenReturn(null);

        assertThatThrownBy(() -> service.revise(APPL_ID, approveRevise()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_207);
    }

    @Test
    void SYSTEM_액터_정정_LOAN_207() {
        when(currentActorProvider.currentActorId()).thenReturn(CurrentActorProvider.SYSTEM);

        assertThatThrownBy(() -> service.revise(APPL_ID, approveRevise()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_207);
    }

    @Test
    void 승인자와_다른_사람이_정정하면_4eye_통과() {
        when(currentActorProvider.currentActorId()).thenReturn(OTHER_ID);

        assertThatCode(() -> service.revise(APPL_ID, approveRevise()))
                .doesNotThrowAnyException();
    }
}
