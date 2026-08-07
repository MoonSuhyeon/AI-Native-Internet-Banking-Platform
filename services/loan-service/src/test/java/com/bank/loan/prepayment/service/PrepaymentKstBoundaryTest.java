package com.bank.loan.prepayment.service;

import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.contract.domain.LoanContract;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 중도상환의 KST 날짜 경계 회귀 테스트.
 *
 * <p>여기는 날짜에 걸리는 계산이 <b>둘</b>이다.
 * <ul>
 *   <li>잔여 연체이자 합 — 하루 차이가 하루치 이자로 나타난다</li>
 *   <li>중도상환 수수료 — 경과개월이 하루 차이로 한 달 어긋나면 잔여기간이 바뀌어
 *       수수료가 통째로 달라진다. 월 기준 경계에 걸리면 금액 차이가 이자보다 크다.</li>
 * </ul>
 *
 * <p>clock 을 UTC 존으로 주는 이유는 {@link ServerClock} 참고.
 */
class PrepaymentKstBoundaryTest {

    private static final long CNTR_ID = 300L;
    private static final long PRINCIPAL = 3_000_000L;
    private static final int OVERDUE_RATE_BPS = 1_000;
    private static final String DUE_DATE = "20260801";
    private static final String VERSION = "V1";

    private static PrepaymentService serviceAt(String kstWallClock, boolean withOverdueSchedule) {
        DelinquencyRepository delinquencyRepository = mock(DelinquencyRepository.class);
        when(delinquencyRepository.findByCntrIdAndDlqStatusCdAndDeletedAtIsNull(anyLong(), any()))
                .thenReturn(Optional.of(Delinquency.builder()
                        .cntrId(CNTR_ID)
                        .dlqStatusCd(Delinquency.STATUS_ACTIVE)
                        .overdueRateBps(OVERDUE_RATE_BPS)
                        .build()));

        RepaymentScheduleRepository scheduleRepository = mock(RepaymentScheduleRepository.class);
        when(scheduleRepository.findActiveByVersion(anyLong(), anyString()))
                .thenReturn(withOverdueSchedule
                        ? List.of(RepaymentSchedule.builder()
                                .rschId(1L)
                                .cntrId(CNTR_ID)
                                .dueDate(DUE_DATE)
                                .scheduledPrincipal(PRINCIPAL)
                                .rschStatusCd(RepaymentSchedule.STATUS_OVERDUE)
                                .build())
                        : List.of());

        RepaymentTransactionRepository txRepository = mock(RepaymentTransactionRepository.class);
        when(txRepository.sumPaidOverdueInterestByRschId(anyLong())).thenReturn(0L);

        return new PrepaymentService(
                txRepository,
                scheduleRepository,
                mock(LoanContractRepository.class),
                delinquencyRepository,
                mock(StatusHistoryPublisher.class),
                mock(CurrentActorProvider.class),
                ServerClock.atKst(kstWallClock));
    }

    private static long overdueSumAt(String kstWallClock) {
        return serviceAt(kstWallClock, true)
                .computeOverdueInterestSum(CNTR_ID, VERSION, ServerClock.nowAtKst(kstWallClock));
    }

    private static long expectedOverdue(int days) {
        return OverdueInterestCalculator.compute(PRINCIPAL, OVERDUE_RATE_BPS, days);
    }

    // ── 잔여 연체이자 ────────────────────────────────────────────────

    @Test
    @DisplayName("KST 08:30 — 연체이자 합이 6일치다 (예전엔 5일치였다)")
    void overdueSumBeforeNineKst() {
        assertThat(overdueSumAt("2026-08-07T08:30:00")).isEqualTo(expectedOverdue(6));
    }

    @Test
    @DisplayName("연체이자 합이 하루 늘어나는 지점은 KST 자정이다")
    void overdueSumRollsOverAtKstMidnight() {
        assertThat(overdueSumAt("2026-08-06T23:59:59")).isEqualTo(expectedOverdue(5));
        assertThat(overdueSumAt("2026-08-07T00:00:01")).isEqualTo(expectedOverdue(6));
    }

    // ── 중도상환 수수료 ──────────────────────────────────────────────

    /** 계약 개시 20260107, 12개월. 08-07 이 7개월째 응당일이라 경계가 여기 걸린다. */
    private static LoanContract contract() {
        return LoanContract.builder()
                .cntrId(CNTR_ID)
                .cntrStartDate("20260107")
                .contractedPeriodMo(12)
                .build();
    }

    private static long feeAt(String kstWallClock) {
        return serviceAt(kstWallClock, false)
                .computeEarlyRepaymentFee(contract(), 10_000_000L, ServerClock.nowAtKst(kstWallClock));
    }

    @Test
    @DisplayName("응당일 아침 KST 08:30 의 수수료는 그날 오후와 같다 — 경과개월이 흔들리면 안 된다")
    void feeIsStableAcrossNineAm() {
        assertThat(feeAt("2026-08-07T08:30:00")).isEqualTo(feeAt("2026-08-07T15:00:00"));
    }

    @Test
    @DisplayName("응당일에 경과 7개월로 잡혀 잔여 5개월 기준 수수료가 나온다")
    void feeUsesKstElapsedMonths() {
        long expected = EarlyRepaymentFeePolicy.calculate(10_000_000L, 12, 5);

        assertThat(feeAt("2026-08-07T08:30:00")).isEqualTo(expected);
    }

    @Test
    @DisplayName("응당일 전날에는 경과 6개월 — 잔여 6개월이라 수수료가 더 크다")
    void feeIsLargerBeforeAnniversary() {
        long dayBefore = feeAt("2026-08-06T23:59:59");
        long onDay = feeAt("2026-08-07T00:00:01");

        assertThat(dayBefore).isEqualTo(EarlyRepaymentFeePolicy.calculate(10_000_000L, 12, 6));
        assertThat(dayBefore).isGreaterThan(onDay);
    }
}
