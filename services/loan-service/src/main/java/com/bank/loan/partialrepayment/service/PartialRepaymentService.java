package com.bank.loan.partialrepayment.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.accrual.repository.InterestAccrualRepository;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.delinquency.domain.Delinquency;
import com.bank.loan.delinquency.repository.DelinquencyRepository;
import com.bank.loan.partialrepayment.dto.PartialRepayRequest;
import com.bank.loan.partialrepayment.dto.PartialRepaymentResponse;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.repayment.service.OverdueInterestCalculator;
import com.bank.loan.repayment.service.PaymentAllocator;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 회차 부분상환(Partial Repayment) 서비스 — TYPE_PARTIAL.
 *
 * 처리 절차:
 *   1) 멱등성 키 검사
 *   2) 계약 + 최신 버전 회차 조회. 회차 상태가 DUE/OVERDUE/PARTIAL_PAID 아니면 LOAN_091.
 *   3) cumulative = sumPaidByRschId(rschId), remaining = scheduledTotal - cumulative
 *      amount > remaining 이면 LOAN_098.
 *   4) 분배 — 순차 분배 (flows §2.2 정석: 연체이자 → 정상이자 → 원금 → 수수료).
 *      본 단계는 수수료 0 가정이라 1·2·3 단계만 적용:
 *        actualOverdue       = 회차가 OVERDUE 일 때 활성 Delinquency 기반 산정
 *                              overdueBase × overdueRateBps × days / 10000 / 365
 *                              (회차가 DUE/PARTIAL_PAID 이면 0 — 본 단계 단순화)
 *        actualInterest      = 회차 기간(prev_due_date, due_date] daily_interest_amt 합
 *                              (배치 미실행 시 scheduled_interest 로 fallback)
 *        remainingOverdue    = max(0, actualOverdue - paidOverdueCumulative)
 *        remainingInterest   = max(0, actualInterest - paidInterestCumulative)
 *        overduePortion      = min(amount,          remainingOverdue)
 *        interestPortion     = min(amount-overdue,  remainingInterest)
 *        principalPortion    = amount - overduePortion - interestPortion
 *   5) RepaymentTransaction 신규 row (TYPE_PARTIAL, SUCCESS).
 *   6) cumulative + amount == scheduledTotal 이면 markPaid(), 아니면 markPartialPaid().
 *      회차 status 변경 시에만 status_history publish (PARTIAL_PAID → PARTIAL_PAID 는 발행 안 함).
 *
 * 수수료 분배(4단계) 통합은 본 단계 외 — LoanProduct 수수료 정책 도입 시 후속.
 * RepaymentService.repayInstallment(정확액 회차상환) 의 연체이자 통합은 본 단계 외.
 */
@Service
@RequiredArgsConstructor
public class PartialRepaymentService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "REPAYMENT_SCHEDULE";
    private static final String REASON_PARTIAL_PAID = "INSTALLMENT_PARTIAL_PAID";
    private static final String REASON_FULLY_PAID   = "INSTALLMENT_PAID";
    private static final String DEFAULT_CHANNEL = "MANUAL";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RepaymentTransactionRepository txRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final LoanContractRepository contractRepository;
    private final InterestAccrualRepository accrualRepository;
    private final DelinquencyRepository delinquencyRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;

    /** 테스트에서 날짜 경계를 고정할 수 있게 주입받는다. ClockConfig 참고. */
    private final Clock clock;

    @Transactional
    public PartialRepaymentResponse repay(Long cntrId, PartialRepayRequest req, String idempotencyKey) {
        // 1) 멱등성
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = txRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                RepaymentTransaction tx = existing.get();
                RepaymentSchedule schedule = scheduleRepository.findById(tx.getRschId())
                        .filter(s -> s.getDeletedAt() == null)
                        .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_090));
                long cumulative = txRepository.sumPaidByRschId(schedule.getRschId());
                return PartialRepaymentResponse.of(tx, schedule, cumulative);
            }
        }

        // 2) 계약·회차 조회
        LoanContract contract = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        String version = resolveLatestVersion(cntrId);
        RepaymentSchedule schedule = scheduleRepository
                .findByCntrIdAndInstallmentNoAndRschVersionCdAndDeletedAtIsNull(
                        cntrId, req.installmentNo(), version)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_090,
                        "cntrId=" + cntrId + ", installmentNo=" + req.installmentNo()
                                + ", version=" + version));
        if (!schedule.isPartialPayable()) {
            throw new BusinessException(LoanErrorCode.LOAN_091,
                    "current=" + schedule.currentStatus());
        }

        // 3) 잔액 검증
        long cumulative = txRepository.sumPaidByRschId(schedule.getRschId());
        long remaining = schedule.getScheduledTotal() - cumulative;
        if (req.amount() > remaining) {
            throw new BusinessException(LoanErrorCode.LOAN_098,
                    "amount=" + req.amount() + ", remaining=" + remaining);
        }

        // 4) 분배 — 연체이자 → 정상이자 → 원금
        OffsetDateTime now = OffsetDateTime.now(clock);

        long actualOverdue = computeOverdueInterest(schedule, now);
        long paidOverdueCumulative = txRepository.sumPaidOverdueInterestByRschId(schedule.getRschId());
        long remainingOverdue = Math.max(0L, actualOverdue - paidOverdueCumulative);

        long actualInterest = computeAccruedInterest(contract, schedule);
        long paidInterestCumulative = txRepository.sumPaidInterestByRschId(schedule.getRschId());
        long remainingInterest = Math.max(0L, actualInterest - paidInterestCumulative);

        PaymentAllocator.Allocation alloc = PaymentAllocator.allocate(
                req.amount(), remainingOverdue, remainingInterest, 0L);

        long newCumulative = cumulative + req.amount();
        boolean fullyPaid = (newCumulative == schedule.getScheduledTotal());

        // 5) tx 저장
        RepaymentTransaction saved = txRepository.save(RepaymentTransaction.builder()
                .cntrId(cntrId)
                .rschId(schedule.getRschId())
                .rtxTypeCd(RepaymentTransaction.TYPE_PARTIAL)
                .totalAmount(req.amount())
                .principalAmount(alloc.principal())
                .interestAmount(alloc.interest())
                .overdueInterestAmount(alloc.overdue())
                .feeAmount(alloc.fee())
                .currencyCd(contract.getCurrencyCd())
                .channelCd(req.channelCd() == null ? DEFAULT_CHANNEL : req.channelCd())
                .rtxStatusCd(RepaymentTransaction.STATUS_SUCCESS)
                .paidAt(now)
                .valueDate(req.valueDate())
                .balanceAfter(schedule.getScheduledTotal() - newCumulative)
                .idempotencyKey(idempotencyKey)
                .reversalYn(false)
                .build());

        // 6) 회차 상태 전이 (변경 시에만 publish)
        String before = schedule.currentStatus();
        if (fullyPaid) {
            schedule.markPaid();
            statusHistoryPublisher.publish(StatusChangeEvent.of(
                    DOMAIN_CD, TARGET_TABLE_CD, schedule.getRschId(),
                    before, RepaymentSchedule.STATUS_PAID,
                    REASON_FULLY_PAID,
                    "rtxId=" + saved.getRtxId() + " (final partial)",
                    currentActor.currentActorId()
            ));
        } else if (!RepaymentSchedule.STATUS_PARTIAL_PAID.equals(before)) {
            schedule.markPartialPaid();
            statusHistoryPublisher.publish(StatusChangeEvent.of(
                    DOMAIN_CD, TARGET_TABLE_CD, schedule.getRschId(),
                    before, RepaymentSchedule.STATUS_PARTIAL_PAID,
                    REASON_PARTIAL_PAID,
                    "rtxId=" + saved.getRtxId() + ", cumulative=" + newCumulative,
                    currentActor.currentActorId()
            ));
        }

        return PartialRepaymentResponse.of(saved, schedule, newCumulative);
    }

    /**
     * 회차당 연체이자 산정. 회차 상태가 OVERDUE 이고 활성 Delinquency 가 있을 때만 양수.
     * 그 외(DUE/PARTIAL_PAID/PAID 등) 는 0 — 본 단계 단순화.
     *
     * 일수 = due_date+1 부터 오늘까지. overdueBase = scheduled_principal (단순화).
     */
    // package-private: 날짜 경계에서만 틀어지는 계산이라 이 메서드만 떼어 검증한다.
    // 통합 테스트로는 KST 00:00~09:00 이라는 조건을 만들기 어렵다.
    // PartialRepaymentKstBoundaryTest 참고.
    long computeOverdueInterest(RepaymentSchedule schedule, OffsetDateTime now) {
        if (!schedule.isOverdue()) return 0L;
        Optional<Delinquency> activeDlq = delinquencyRepository
                .findByCntrIdAndDlqStatusCdAndDeletedAtIsNull(
                        schedule.getCntrId(), Delinquency.STATUS_ACTIVE);
        if (activeDlq.isEmpty()) return 0L;
        int overdueRateBps = activeDlq.get().getOverdueRateBps();
        if (overdueRateBps <= 0) return 0L;

        LocalDate dueDate = LocalDate.parse(schedule.getDueDate(), DATE);
        LocalDate today = BusinessDate.dateOf(now);
        int days = (int) ChronoUnit.DAYS.between(dueDate, today);
        if (days <= 0) return 0L;

        return OverdueInterestCalculator.compute(schedule.getScheduledPrincipal(), overdueRateBps, days);
    }

    /**
     * 회차 귀속 기간(prev_due_date, due_date] InterestAccrual.daily_interest_amt 합.
     * 첫 회차는 cntr_start_date 기준, 이전 회차는 같은 버전에서 조회.
     * accrual 배치가 한 번도 안 돌았으면 scheduled_interest 로 fallback.
     */
    private long computeAccruedInterest(LoanContract contract, RepaymentSchedule schedule) {
        String fromExclusive;
        if (schedule.getInstallmentNo() == 1) {
            fromExclusive = contract.getCntrStartDate();
        } else {
            fromExclusive = scheduleRepository
                    .findByCntrIdAndInstallmentNoAndRschVersionCdAndDeletedAtIsNull(
                            schedule.getCntrId(),
                            schedule.getInstallmentNo() - 1,
                            schedule.getRschVersionCd())
                    .map(RepaymentSchedule::getDueDate)
                    .orElse(contract.getCntrStartDate());
        }
        long actual = accrualRepository.sumDailyInterestInRange(
                schedule.getCntrId(), fromExclusive, schedule.getDueDate());
        return actual > 0 ? actual : schedule.getScheduledInterest();
    }

    private String resolveLatestVersion(Long cntrId) {
        String max = scheduleRepository.findMaxVersion(cntrId);
        return (max == null || max.isBlank()) ? RepaymentSchedule.VERSION_INITIAL : max;
    }
}
