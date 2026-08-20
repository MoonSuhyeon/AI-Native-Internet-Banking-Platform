package com.bank.deposit.domain;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.PaymentStatus;
import com.bank.deposit.dto.response.SavingsPaymentStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적금 납입현황 집계.
 *
 * <p><b>무엇을 지키는가.</b> "밀려 있다" 가 화면에 드러나게 한다. 납입한 회차만 세면
 * 3회 중 1회만 낸 계좌도 "1회 납입" 으로 보여서 정상처럼 읽힌다 — 고객이 문제를
 * 알아채는 시점이 만기 이자 계산 때로 미뤄진다.
 *
 * <p>당일은 미납으로 세지 않는다. 오늘이 납입일인데 아직 안 낸 것을 연체로 표시하면
 * 아침마다 모든 고객이 밀린 것으로 보인다.
 */
class SavingsPaymentStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 20);

    private static Contract contract() {
        return Contract.builder()
                .contractId(10L)
                .contractNumber("C-0001")
                .joinAmount(300_000L)
                .paymentCountTotal(12)
                .startedAt(LocalDate.of(2026, 5, 10))
                .maturityAt(LocalDate.of(2027, 5, 10))
                .build();
    }

    private static PaymentSchedule round(int no, LocalDate date, PaymentStatus status) {
        return PaymentSchedule.builder()
                .contractId(10L)
                .accountId(1L)
                .paymentRound(no)
                .scheduledDate(date)
                .scheduledAmount(300_000L)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("지난 예정일의 미납 회차를 센다")
    void countsMissedRounds() {
        var res = SavingsPaymentStatusResponse.of(1L, contract(), 300_000L, List.of(
                round(1, LocalDate.of(2026, 5, 10), PaymentStatus.PAID),
                round(2, LocalDate.of(2026, 6, 10), PaymentStatus.PENDING),
                round(3, LocalDate.of(2026, 7, 10), PaymentStatus.PENDING),
                round(4, LocalDate.of(2026, 9, 10), PaymentStatus.PENDING)
        ), TODAY);

        assertThat(res.paidRounds()).isEqualTo(1);
        assertThat(res.missedRounds()).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘이 예정일이면 아직 미납이 아니다")
    void todayIsNotMissedYet() {
        var res = SavingsPaymentStatusResponse.of(1L, contract(), 0L, List.of(
                round(1, TODAY, PaymentStatus.PENDING)
        ), TODAY);

        assertThat(res.missedRounds()).isZero();
    }

    @Test
    @DisplayName("배치가 표시한 미납도 함께 센다")
    void countsBatchMarkedMissed() {
        // 배치가 OVERDUE·FAILED·SUSPENDED 로 바꾸고 나면 상태가 PENDING 이 아니게 된다.
        // 지난 예정일의 PENDING 만 세면 그 순간 미납 수가 0 으로 떨어진다.
        var res = SavingsPaymentStatusResponse.of(1L, contract(), 0L, List.of(
                round(1, LocalDate.of(2026, 5, 10), PaymentStatus.OVERDUE),
                round(2, LocalDate.of(2026, 6, 10), PaymentStatus.FAILED),
                round(3, LocalDate.of(2026, 7, 10), PaymentStatus.SUSPENDED),
                round(4, LocalDate.of(2026, 9, 10), PaymentStatus.PENDING)
        ), TODAY);

        assertThat(res.missedRounds()).isEqualTo(3);
        assertThat(res.paidRounds()).isZero();
    }

    @Test
    @DisplayName("다음 납입일은 오늘 이후의 가장 이른 예정일이다")
    void nextScheduledDateSkipsPast() {
        var res = SavingsPaymentStatusResponse.of(1L, contract(), 0L, List.of(
                round(1, LocalDate.of(2026, 6, 10), PaymentStatus.PENDING),
                round(2, LocalDate.of(2026, 10, 10), PaymentStatus.PENDING),
                round(3, LocalDate.of(2026, 9, 10), PaymentStatus.PENDING)
        ), TODAY);

        // 밀린 6월분이 다음 납입일로 나오면 화면이 "다음 납입 2026-06-10" 이라는
        // 이미 지난 날짜를 안내한다.
        assertThat(res.nextScheduledDate()).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    @DisplayName("전 회차를 냈으면 다음 납입일이 없다")
    void noNextWhenAllPaid() {
        var res = SavingsPaymentStatusResponse.of(1L, contract(), 600_000L, List.of(
                round(1, LocalDate.of(2026, 5, 10), PaymentStatus.PAID),
                round(2, LocalDate.of(2026, 6, 10), PaymentStatus.PAID)
        ), TODAY);

        assertThat(res.nextScheduledDate()).isNull();
        assertThat(res.missedRounds()).isZero();
    }

    @Test
    @DisplayName("계약에 총 회차가 없으면 스케줄 수로 채운다")
    void fallsBackToScheduleCount() {
        // 자유적금은 총 회차가 정해지지 않는다. 여기서 0 이나 null 을 내보내면
        // 화면의 "n/총" 표시가 "3/0" 처럼 나온다.
        Contract free = Contract.builder()
                .contractId(10L).contractNumber("C-0002").joinAmount(100_000L)
                .startedAt(LocalDate.of(2026, 5, 10))
                .build();

        var res = SavingsPaymentStatusResponse.of(1L, free, 0L, List.of(
                round(1, LocalDate.of(2026, 5, 10), PaymentStatus.PAID),
                round(2, LocalDate.of(2026, 6, 10), PaymentStatus.PAID)
        ), TODAY);

        assertThat(res.totalRounds()).isEqualTo(2);
    }
}
