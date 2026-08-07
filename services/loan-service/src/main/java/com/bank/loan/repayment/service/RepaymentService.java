package com.bank.loan.repayment.service;

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
import com.bank.loan.notification.event.InstallmentPaidEvent;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.dto.RepayInstallmentRequest;
import com.bank.loan.repayment.dto.RepaymentTransactionListResponse;
import com.bank.loan.repayment.dto.RepaymentTransactionResponse;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 상환 처리 (수동/창구) 서비스. 회차 정확액 상환 (TYPE_SCHEDULED).
 *
 * 분배 순서(flows §2.2): 연체이자 → 정상이자 → 원금 → 수수료. PaymentAllocator 위임.
 *
 *   DUE 회차:     totalAmount = scheduled_total
 *                  overdue=0, interest=computeInterestPortion, principal=total-interest
 *   OVERDUE 회차: totalAmount = scheduled_total + computed_overdue_interest
 *                  overdue=computed_overdue, interest=scheduled_interest, principal=scheduled_principal
 *
 * 수수료는 본 서비스 범위 외 (중도상환 수수료는 PrepaymentService 책임).
 *
 * 멱등성: Idempotency-Key 헤더로 보호. 동일 키 재호출 시 기존 tx 반환.
 *
 * 상태 전이:
 *   REPAYMENT_SCHEDULE: DUE/OVERDUE → PAID (status_history 기록)
 *   REPAYMENT_TRANSACTION: 신규 row, status=SUCCESS
 *
 * 이자 정산:
 *   회차 기간(prev_due_date, due_date] 의 InterestAccrual.daily_interest_amt 합을 사용.
 *   accrual 배치가 회차 기간에 한 번도 돌지 않았으면(0) scheduled_interest 로 fallback.
 *
 * 연체이자 산정 (OVERDUE 회차):
 *   formula = scheduled_principal × overdueRateBps × days / 10000 / 365  (HALF_EVEN)
 *   days = ChronoUnit.DAYS.between(dueDate, today). 활성 Delinquency 가 없으면 0.
 */
@Service
@RequiredArgsConstructor
public class RepaymentService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "REPAYMENT_SCHEDULE";
    private static final String REASON_INSTALLMENT_PAID = "INSTALLMENT_PAID";
    private static final String DEFAULT_CHANNEL = "MANUAL";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RepaymentTransactionRepository txRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final LoanContractRepository contractRepository;
    private final InterestAccrualRepository accrualRepository;
    private final DelinquencyRepository delinquencyRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final ApplicationEventPublisher eventPublisher;

    /** 테스트에서 날짜 경계를 고정할 수 있게 주입받는다. ClockConfig 참고. */
    private final Clock clock;

    @Transactional
    public RepaymentTransactionResponse repayInstallment(Long cntrId, RepayInstallmentRequest req, String idempotencyKey) {
        return repayInstallment(cntrId, req, idempotencyKey, null, null);
    }

    @Transactional
    public RepaymentTransactionResponse repayInstallment(Long cntrId, RepayInstallmentRequest req, String idempotencyKey, String paymentStatus) {
        return repayInstallment(cntrId, req, idempotencyKey, paymentStatus, null);
    }

    /**
     * paymentStatus=FAILED: payment-service 출금 실패. schedule 상태 유지, FAILED tx 저장.
     * paymentStatus=null or COMPLETED: 기존 성공 경로.
     * piId: payment-service 결제지시 ID. CLEARING 콜백 추적에 사용.
     */
    @Transactional
    public RepaymentTransactionResponse repayInstallment(Long cntrId, RepayInstallmentRequest req, String idempotencyKey, String paymentStatus, String piId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = txRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return RepaymentTransactionResponse.of(existing.get());
            }
        }

        LoanContract contract = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        RepaymentSchedule schedule = scheduleRepository
                .findByCntrIdAndInstallmentNoAndRschVersionCdAndDeletedAtIsNull(
                        cntrId, req.installmentNo(), RepaymentSchedule.VERSION_INITIAL)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_090,
                        "cntrId=" + cntrId + ", installmentNo=" + req.installmentNo()));

        if (RepaymentTransaction.STATUS_FAILED.equals(paymentStatus)) {
            RepaymentTransaction failed = txRepository.save(RepaymentTransaction.builder()
                    .cntrId(cntrId)
                    .rschId(schedule.getRschId())
                    .rtxTypeCd(RepaymentTransaction.TYPE_SCHEDULED)
                    .totalAmount(0L)
                    .principalAmount(0L)
                    .interestAmount(0L)
                    .overdueInterestAmount(0L)
                    .feeAmount(0L)
                    .currencyCd(contract.getCurrencyCd())
                    .channelCd(req.channelCd() == null ? DEFAULT_CHANNEL : req.channelCd())
                    .rtxStatusCd(RepaymentTransaction.STATUS_FAILED)
                    .paidAt(OffsetDateTime.now(clock))
                    .valueDate(req.valueDate())
                    .balanceAfter(null)
                    .idempotencyKey(idempotencyKey)
                    .reversalYn(RepaymentTransaction.YN_N)
                    .piId(piId)
                    .build());
            return RepaymentTransactionResponse.of(failed);
        }

        // SUPERSEDED 등 DUE/OVERDUE 가 아닌 상태는 즉시 차단
        if (!schedule.isPayable()) {
            throw new BusinessException(LoanErrorCode.LOAN_091,
                    "current=" + schedule.currentStatus());
        }

        // 조건부 UPDATE — 조회와 갱신을 DB 단일 쿼리로 원자 처리.
        // DUE/OVERDUE 상태인 경우에만 PAID 로 전이하며, affected=0 은 다른 요청이 선점했음을 의미.
        String before = schedule.currentStatus();
        int affected = scheduleRepository.claimStatusChange(
                schedule.getRschId(),
                RepaymentSchedule.STATUS_PAID,
                List.of(RepaymentSchedule.STATUS_DUE, RepaymentSchedule.STATUS_OVERDUE));
        if (affected == 0) {
            throw new BusinessException(LoanErrorCode.LOAN_091,
                    "installmentNo=" + req.installmentNo());
        }

        // 분배 정산 — 회차 기간 발생이자 + (OVERDUE 시) 연체이자.
        // OVERDUE: totalAmount = scheduled_total + computed_overdue, 분배는 overdue→interest→principal
        // DUE:     totalAmount = scheduled_total, overdue=0
        OffsetDateTime now = OffsetDateTime.now(clock);
        long overdueInterest = computeOverdueInterest(schedule, now);
        long scheduledInterest = computeInterestPortion(contract, schedule);
        long total = schedule.getScheduledTotal() + overdueInterest;
        PaymentAllocator.Allocation alloc = PaymentAllocator.allocate(
                total, overdueInterest, scheduledInterest, 0L);

        RepaymentTransaction saved = txRepository.save(RepaymentTransaction.builder()
                .cntrId(cntrId)
                .rschId(schedule.getRschId())
                .rtxTypeCd(RepaymentTransaction.TYPE_SCHEDULED)
                .totalAmount(total)
                .principalAmount(alloc.principal())
                .interestAmount(alloc.interest())
                .overdueInterestAmount(alloc.overdue())
                .feeAmount(alloc.fee())
                .currencyCd(contract.getCurrencyCd())
                .channelCd(req.channelCd() == null ? DEFAULT_CHANNEL : req.channelCd())
                .rtxStatusCd(RepaymentTransaction.STATUS_SUCCESS)
                .paidAt(now)
                .valueDate(req.valueDate())
                .balanceAfter(schedule.getRemainingBalance())
                .idempotencyKey(idempotencyKey)
                .reversalYn(RepaymentTransaction.YN_N)
                .piId(piId)
                .build());
        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, schedule.getRschId(),
                before, RepaymentSchedule.STATUS_PAID,
                REASON_INSTALLMENT_PAID,
                "rtxId=" + saved.getRtxId(),
                currentActor.currentActorId()
        ));

        eventPublisher.publishEvent(new InstallmentPaidEvent(
                saved.getRtxId(), cntrId, schedule.getRschId(),
                schedule.getInstallmentNo(), saved.getTotalAmount(),
                saved.getChannelCd()
        ));

        return RepaymentTransactionResponse.of(saved);
    }

    /**
     * 회차 귀속 기간의 발생이자 합. 이전 회차(installmentNo-1, 같은 버전) 의 due_date 가 from(exclusive),
     * 이번 회차의 due_date 가 to(inclusive). 첫 회차는 contract.cntr_start_date 기준.
     * accrual 배치가 한 번도 돌지 않은 경우(0) scheduled_interest 로 fallback.
     */
    private long computeInterestPortion(LoanContract contract, RepaymentSchedule schedule) {
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

    /**
     * 회차당 연체이자 산정 — OVERDUE 회차일 때만 양수. PartialRepaymentService 와 동일 산식.
     * 일수 = due_date+1 부터 오늘까지. overdueBase = scheduled_principal (단순화).
     * 활성 Delinquency 가 없거나 days≤0 이면 0.
     */
    // package-private: 연체일수는 날짜 경계에서만 틀어지므로 이 계산만 떼어 검증한다.
    // 상환 전체 흐름을 통합 테스트로 돌리면 KST 00:00~09:00 이라는 조건을 만들기 어렵다.
    // RepaymentOverdueKstBoundaryTest 참고.
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

    @Transactional(readOnly = true)
    public RepaymentTransactionListResponse list(Long cntrId) {
        contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        List<RepaymentTransactionResponse> items = txRepository
                .findByCntrIdAndDeletedAtIsNullOrderByPaidAtAsc(cntrId)
                .stream()
                .map(RepaymentTransactionResponse::of)
                .toList();
        return RepaymentTransactionListResponse.of(cntrId, items);
    }
}
