package com.bank.deposit.dto.response;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.PaymentStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 적금 납입현황.
 *
 * <p>계약(몇 회를 얼마씩)과 스케줄(회차별로 실제 어떻게 됐는지)을 한 번에 준다.
 * 화면이 둘을 따로 부르면 회차 수와 계약 기간이 어긋난 채 그려질 수 있다 —
 * 두 응답 사이에 납입이 일어나면 그렇게 된다.
 *
 * @param missedRounds 지나갔는데 아직 안 낸 회차. 이걸 세지 않으면 "정상 납입 중"
 *                     으로 보이는데 실제로는 밀려 있는 상태가 화면에 안 드러난다.
 */
public record SavingsPaymentStatusResponse(
        Long accountId,
        Long contractId,
        String contractNumber,
        Long monthlyAmount,
        Integer totalRounds,
        Integer paidRounds,
        Integer missedRounds,
        Long totalPaidAmount,
        LocalDate startedAt,
        LocalDate maturityAt,
        LocalDate nextScheduledDate,
        List<Round> rounds
) {

    /** 배치가 이미 미납으로 표시한 상태들. */
    private static final Set<PaymentStatus> MISSED_STATUSES = EnumSet.of(
            PaymentStatus.OVERDUE, PaymentStatus.FAILED, PaymentStatus.SUSPENDED);

    public record Round(
            Integer paymentRound,
            LocalDate scheduledDate,
            Long scheduledAmount,
            PaymentStatus status,
            OffsetDateTime paidAt,
            Long actualAmount,
            boolean autoTransfer
    ) {
        static Round of(PaymentSchedule s) {
            return new Round(
                    s.getPaymentRound(), s.getScheduledDate(), s.getScheduledAmount(),
                    s.getStatus(), s.getPaidAt(), s.getActualAmount(),
                    Boolean.TRUE.equals(s.getIsAutoTransfer()));
        }
    }

    public static SavingsPaymentStatusResponse of(
            Long accountId, Contract contract, Long totalPaidAmount,
            List<PaymentSchedule> schedules, LocalDate today) {

        int paid = (int) schedules.stream()
                .filter(s -> s.getStatus() == PaymentStatus.PAID)
                .count();

        // 안 낸 회차. 배치가 이미 OVERDUE·FAILED·SUSPENDED 로 표시한 것과, 아직
        // PENDING 인데 예정일이 지난 것을 함께 센다 — 앞의 셋만 세면 배치가 돌기
        // 전 구간이 비어 보이고, 뒤만 세면 배치가 표시한 뒤로 미납이 사라진다.
        // 오늘은 유예한다. 오늘 낼 수도 있는 것을 연체로 표시하면 아침마다 모든
        // 고객이 밀린 것으로 보인다.
        int missed = (int) schedules.stream()
                .filter(s -> MISSED_STATUSES.contains(s.getStatus())
                        || (s.getStatus() == PaymentStatus.PENDING
                            && s.getScheduledDate().isBefore(today)))
                .count();

        LocalDate next = schedules.stream()
                .filter(s -> s.getStatus() == PaymentStatus.PENDING)
                .filter(s -> !s.getScheduledDate().isBefore(today))
                .map(PaymentSchedule::getScheduledDate)
                .min(LocalDate::compareTo)
                .orElse(null);

        return new SavingsPaymentStatusResponse(
                accountId,
                contract.getContractId(),
                contract.getContractNumber(),
                contract.getJoinAmount(),
                contract.getPaymentCountTotal() != null
                        ? contract.getPaymentCountTotal()
                        : schedules.size(),
                paid,
                missed,
                totalPaidAmount,
                contract.getStartedAt(),
                contract.getMaturityAt(),
                next,
                schedules.stream().map(Round::of).toList());
    }
}
