package com.bank.loan.partialrepayment.service;

import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.accrual.repository.InterestAccrualRepository;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.delinquency.domain.Delinquency;
import com.bank.loan.delinquency.repository.DelinquencyRepository;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.repayment.service.OverdueInterestCalculator;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.ServerClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 부분상환의 연체이자 KST 날짜 경계 회귀 테스트.
 *
 * <p>{@code RepaymentService} 와 같은 계산이 별도로 한 벌 더 있다. 같은 결함이 있었고 같이
 * 고쳤으므로 방어선도 같이 둔다 — 한쪽만 테스트하면 다른 쪽이 조용히 되돌아가도 아무도 모른다.
 *
 * <p>clock 을 UTC 존으로 주는 이유는 {@link ServerClock} 참고.
 */
class PartialRepaymentKstBoundaryTest {

    private static final long CNTR_ID = 200L;
    private static final long PRINCIPAL = 2_000_000L;
    private static final int OVERDUE_RATE_BPS = 1_500;
    private static final String DUE_DATE = "20260801";

    private static PartialRepaymentService serviceAt(String kstWallClock) {
        DelinquencyRepository delinquencyRepository = mock(DelinquencyRepository.class);
        when(delinquencyRepository.findByCntrIdAndDlqStatusCdAndDeletedAtIsNull(anyLong(), any()))
                .thenReturn(Optional.of(Delinquency.builder()
                        .cntrId(CNTR_ID)
                        .dlqStatusCd(Delinquency.STATUS_ACTIVE)
                        .overdueRateBps(OVERDUE_RATE_BPS)
                        .build()));

        return new PartialRepaymentService(
                mock(RepaymentTransactionRepository.class),
                mock(RepaymentScheduleRepository.class),
                mock(LoanContractRepository.class),
                mock(InterestAccrualRepository.class),
                delinquencyRepository,
                mock(StatusHistoryPublisher.class),
                mock(CurrentActorProvider.class),
                ServerClock.atKst(kstWallClock));
    }

    private static RepaymentSchedule overdueSchedule() {
        return RepaymentSchedule.builder()
                .cntrId(CNTR_ID)
                .dueDate(DUE_DATE)
                .scheduledPrincipal(PRINCIPAL)
                .rschStatusCd(RepaymentSchedule.STATUS_OVERDUE)
                .build();
    }

    private static long overdueInterestAt(String kstWallClock) {
        return serviceAt(kstWallClock)
                .computeOverdueInterest(overdueSchedule(), ServerClock.nowAtKst(kstWallClock));
    }

    private static long expected(int days) {
        return OverdueInterestCalculator.compute(PRINCIPAL, OVERDUE_RATE_BPS, days);
    }

    @Test
    @DisplayName("KST 08:30 — 6일치로 계산된다 (예전엔 5일치였다)")
    void beforeNineKstCountsToday() {
        assertThat(overdueInterestAt("2026-08-07T08:30:00")).isEqualTo(expected(6));
    }

    @Test
    @DisplayName("같은 날 08:30 과 10:30 이 같다")
    void sameDayIsStable() {
        assertThat(overdueInterestAt("2026-08-07T08:30:00"))
                .isEqualTo(overdueInterestAt("2026-08-07T10:30:00"));
    }

    @Test
    @DisplayName("하루가 늘어나는 지점은 KST 자정이다")
    void rollsOverAtKstMidnight() {
        assertThat(overdueInterestAt("2026-08-06T23:59:59")).isEqualTo(expected(5));
        assertThat(overdueInterestAt("2026-08-07T00:00:01")).isEqualTo(expected(6));
    }

    @Test
    @DisplayName("만기 당일 아침에는 0 이다")
    void noChargeOnDueDate() {
        assertThat(overdueInterestAt("2026-08-01T08:30:00")).isZero();
    }
}
