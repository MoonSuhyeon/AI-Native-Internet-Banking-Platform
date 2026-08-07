package com.bank.loan.repayment.service;

import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.accrual.repository.InterestAccrualRepository;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.delinquency.domain.Delinquency;
import com.bank.loan.delinquency.repository.DelinquencyRepository;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.ServerClock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 연체이자 산정의 KST 날짜 경계 회귀 테스트.
 *
 * <p><b>무엇을 막는가.</b> 연체일수는 {@code between(dueDate, today)} 로 구한다. 예전에는
 * {@code today} 를 {@code OffsetDateTime.now().toLocalDate()} 로 얻었고, 컨테이너 JVM
 * 타임존이 UTC 라 한국 시간 00:00~09:00 에는 하루 전 날짜가 나왔다. 그 9시간 동안 연체이자가
 * 하루치 적게 계산됐다 — 같은 대출을 아침 8시에 조회할 때와 10시에 조회할 때 금액이 달랐다.
 *
 * <p><b>왜 clock 을 UTC 존으로 주는가.</b> 이 점이 이 테스트의 핵심이다. 지금 방어선은 두 겹이다.
 * <ol>
 *   <li>{@code ClockConfig} 가 KST clock 을 준다 → {@code now} 가 +09:00 을 달고 온다</li>
 *   <li>{@code BusinessDate.dateOf(now)} 가 어떤 오프셋이 오든 KST 로 변환한다</li>
 * </ol>
 * KST clock 으로 테스트하면 (1)에 가려 (2)가 검증되지 않는다. 실제로 처음 이렇게 썼다가
 * 옛 구현에 돌려보니 5개 중 4개가 그냥 통과했다. 그래서 <b>일부러 UTC 존 clock</b> 을 준다 —
 * 컨테이너 기본값이자, 누군가 {@code ClockConfig} 를 되돌렸을 때의 모습이다. 이 상태에서도
 * 날짜가 KST 로 나와야 (2)가 살아 있는 것이다.
 *
 * <p>금액 공식 자체({@link OverdueInterestCalculator})는 일수를 받는 순수 함수라 별도로
 * 검증된다. 여기서 보는 것은 <b>그 일수가 몇으로 정해지는가</b>다.
 */
class RepaymentOverdueKstBoundaryTest {

    private static final long CNTR_ID = 100L;
    private static final long PRINCIPAL = 1_000_000L;
    private static final int OVERDUE_RATE_BPS = 1_200;   // 연 12%
    private static final String DUE_DATE = "20260801";

    /** 연체이자 계산에 실제로 관여하는 것은 Delinquency 조회뿐이라 나머지는 빈 목이다. */
    private static RepaymentService serviceAt(String kstWallClock) {
        DelinquencyRepository delinquencyRepository = mock(DelinquencyRepository.class);
        when(delinquencyRepository.findByCntrIdAndDlqStatusCdAndDeletedAtIsNull(anyLong(), any()))
                .thenReturn(Optional.of(Delinquency.builder()
                        .cntrId(CNTR_ID)
                        .dlqStatusCd(Delinquency.STATUS_ACTIVE)
                        .overdueRateBps(OVERDUE_RATE_BPS)
                        .build()));

        return new RepaymentService(
                mock(RepaymentTransactionRepository.class),
                mock(RepaymentScheduleRepository.class),
                mock(LoanContractRepository.class),
                mock(InterestAccrualRepository.class),
                delinquencyRepository,
                mock(StatusHistoryPublisher.class),
                mock(CurrentActorProvider.class),
                mock(ApplicationEventPublisher.class),
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

    /** 운영과 같은 경로로 now 를 얻어 연체이자를 계산한다. */
    private static long overdueInterestAt(String kstWallClock) {
        Clock clock = ServerClock.atKst(kstWallClock);
        return serviceAt(kstWallClock)
                .computeOverdueInterest(overdueSchedule(), OffsetDateTime.now(clock));
    }

    private static long expected(int days) {
        return OverdueInterestCalculator.compute(PRINCIPAL, OVERDUE_RATE_BPS, days);
    }

    @Test
    @DisplayName("전제 확인: 서버 clock 은 UTC 오프셋을 준다 — 이 조건에서 검증해야 의미가 있다")
    void serverClockYieldsUtcOffset() {
        OffsetDateTime now = OffsetDateTime.now(ServerClock.atKst("2026-08-07T08:30:00"));

        assertThat(now.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(now.toLocalDate().toString())
                .as("존을 무시하고 읽으면 하루 전이다")
                .isEqualTo("2026-08-06");
    }

    @Test
    @DisplayName("KST 08:30 — 만기 6일 경과분이 6일치로 계산된다 (예전엔 5일치였다)")
    void beforeNineKstCountsToday() {
        assertThat(overdueInterestAt("2026-08-07T08:30:00")).isEqualTo(expected(6));
    }

    @Test
    @DisplayName("같은 날 08:30 과 10:30 의 연체이자는 같아야 한다 — 예전엔 달랐다")
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
    @DisplayName("만기 당일 아침에는 연체이자가 0 이다 — 하루 앞당겨 물리지 않는다")
    void noChargeOnDueDate() {
        assertThat(overdueInterestAt("2026-08-01T08:30:00")).isZero();
    }

    @Test
    @DisplayName("연체가 해소되면 0 — 날짜와 무관하게 Delinquency 가 조건이다")
    void noChargeWithoutActiveDelinquency() {
        DelinquencyRepository empty = mock(DelinquencyRepository.class);
        when(empty.findByCntrIdAndDlqStatusCdAndDeletedAtIsNull(anyLong(), any()))
                .thenReturn(Optional.empty());

        RepaymentService service = new RepaymentService(
                mock(RepaymentTransactionRepository.class),
                mock(RepaymentScheduleRepository.class),
                mock(LoanContractRepository.class),
                mock(InterestAccrualRepository.class),
                empty,
                mock(StatusHistoryPublisher.class),
                mock(CurrentActorProvider.class),
                mock(ApplicationEventPublisher.class),
                ServerClock.atKst("2026-08-07T08:30:00"));

        assertThat(service.computeOverdueInterest(overdueSchedule(),
                OffsetDateTime.now(ServerClock.atKst("2026-08-07T08:30:00")))).isZero();
    }
}
