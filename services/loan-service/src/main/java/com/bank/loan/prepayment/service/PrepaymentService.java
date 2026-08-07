package com.bank.loan.prepayment.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.delinquency.domain.Delinquency;
import com.bank.loan.delinquency.repository.DelinquencyRepository;
import com.bank.loan.prepayment.dto.PrepayRequest;
import com.bank.loan.prepayment.dto.PrepaymentResponse;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.repayment.service.OverdueInterestCalculator;
import com.bank.loan.repayment.service.PaymentAllocator;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.schedule.service.EqualPaymentCalculator;
import com.bank.loan.schedule.service.RepaymentScheduleService;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 중도상환(Early Repayment) 서비스 — TYPE_EARLY.
 *
 * 처리 절차:
 *   1) 멱등성 키 검사 — 동일 키 재호출 시 기존 tx 반환
 *   2) 계약 ACTIVE 검증 + EQUAL 외 LOAN_084
 *   3) outstanding = contracted - Σ(PAID schedule principal) - Σ(EARLY tx principal)
 *      amount 가 outstanding 을 초과하면 LOAN_094
 *   4) 분배 산정 — 잔여 OVERDUE 회차들의 미수 연체이자 + 중도상환 수수료를 추가 수금.
 *      RepaymentTransaction (TYPE_EARLY, SUCCESS):
 *        principal = amount, overdue = overdueInterestSum, interest = 0, fee = computed
 *        totalAmount = amount + overdueInterest + fee
 *   5) 잔여 스케줄 재생성 (flows §2.3):
 *      - 최신 버전의 DUE/OVERDUE 회차들을 모두 SUPERSEDED — status_history append
 *      - newOutstanding > 0 이면 새 버전(V{n+1}) 으로 회차 재계산해 saveAll
 *        같은 installmentNo / dueDate / appliedRateBps 유지, principal/interest 만 재산정
 *      - newOutstanding == 0 이면 새 회차 없음. 계약 종결은 별도 API (LoanClosureService).
 *
 * 연체이자 산정 (Delinquency.STATUS_ACTIVE 인 경우):
 *   회차당: scheduledPrincipal × overdueRateBps × days / 10000 / 365 (HALF_EVEN)
 *   days = ChronoUnit.DAYS.between(dueDate, today). 이미 partial 로 갚힌 부분은 차감.
 *
 * 단순화 가정:
 *   - 미발생이자(기준일까지 일할 이자) 정산 미고려 — interest_amount = 0
 *   - 수수료는 EarlyRepaymentFeePolicy 잔여기간 비례. 디폴트 150bps. 사용자는 amount 만 입력.
 *   - 부분상환(회차 일부) 미지원 / 역분개 미지원
 */
@Service
@RequiredArgsConstructor
public class PrepaymentService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "REPAYMENT_SCHEDULE";
    private static final String REASON_SUPERSEDED_BY_PREPAY = "SUPERSEDED_BY_PREPAY";
    private static final String DEFAULT_CHANNEL = "MANUAL";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RepaymentTransactionRepository txRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final LoanContractRepository contractRepository;
    private final DelinquencyRepository delinquencyRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;

    /** 테스트에서 날짜 경계를 고정할 수 있게 주입받는다. ClockConfig 참고. */
    private final Clock clock;

    @Transactional
    public PrepaymentResponse prepay(Long cntrId, PrepayRequest req, String idempotencyKey) {
        // 1) 멱등성
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = txRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                RepaymentTransaction tx = existing.get();
                return PrepaymentResponse.of(tx, computeOutstandingExcluding(cntrId, tx),
                        0, null, 0);
            }
        }

        // 2) 계약 검증
        LoanContract contract = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));
        if (!contract.isActive()) {
            throw new BusinessException(LoanErrorCode.LOAN_092,
                    "current=" + contract.currentStatus());
        }
        if (!RepaymentScheduleService.REPAY_METHOD_EQUAL.equals(contract.getRepaymentMethodCd())) {
            throw new BusinessException(LoanErrorCode.LOAN_084,
                    "repaymentMethodCd=" + contract.getRepaymentMethodCd());
        }
        if (req.amount() == null || req.amount() <= 0) {
            throw new BusinessException(LoanErrorCode.LOAN_093);
        }

        // 3) outstanding 계산
        long paidFromSchedule = scheduleRepository.sumPaidPrincipal(cntrId);
        long paidFromPrepay = txRepository.sumEarlyPrincipal(cntrId);
        long outstanding = contract.getContractedAmount() - paidFromSchedule - paidFromPrepay;
        if (outstanding <= 0) {
            throw new BusinessException(LoanErrorCode.LOAN_094,
                    "outstanding=" + outstanding);
        }
        if (req.amount() > outstanding) {
            throw new BusinessException(LoanErrorCode.LOAN_094,
                    "amount=" + req.amount() + ", outstanding=" + outstanding);
        }

        long newOutstanding = outstanding - req.amount();
        OffsetDateTime now = OffsetDateTime.now(clock);

        // 4) 분배 산정 — supersede 전에 잔여 OVERDUE 회차들의 미수 연체이자를 추가 수금.
        //   totalAmount = amount + overdueInterest + fee
        //   principal=amount, overdue=overdueInterest, interest=0, fee=computed
        //   * 정상이자(미발생이자) 일할 정산은 본 단계 외 — 잔여 0 유지.
        String currentVersion = currentVersionOrInitial(cntrId);
        long overdueInterest = computeOverdueInterestSum(cntrId, currentVersion, now);
        long fee = computeEarlyRepaymentFee(contract, req.amount(), now);
        PaymentAllocator.Allocation alloc = PaymentAllocator.allocate(
                req.amount() + overdueInterest, overdueInterest, 0L, fee);
        long totalAmount = req.amount() + overdueInterest + alloc.fee();

        RepaymentTransaction saved = txRepository.save(RepaymentTransaction.builder()
                .cntrId(cntrId)
                .rschId(null)
                .rtxTypeCd(RepaymentTransaction.TYPE_EARLY)
                .totalAmount(totalAmount)
                .principalAmount(alloc.principal())
                .interestAmount(alloc.interest())
                .overdueInterestAmount(alloc.overdue())
                .feeAmount(alloc.fee())
                .currencyCd(contract.getCurrencyCd())
                .channelCd(req.channelCd() == null ? DEFAULT_CHANNEL : req.channelCd())
                .rtxStatusCd(RepaymentTransaction.STATUS_SUCCESS)
                .paidAt(now)
                .valueDate(req.valueDate())
                .balanceAfter(newOutstanding)
                .idempotencyKey(idempotencyKey)
                .reversalYn(RepaymentTransaction.YN_N)
                .build());

        // 5) 스케줄 재생성 (currentVersion 은 위에서 계산)
        List<RepaymentSchedule> active = scheduleRepository.findActiveByVersion(cntrId, currentVersion);
        for (RepaymentSchedule s : active) {
            String before = s.currentStatus();
            s.markSuperseded();
            statusHistoryPublisher.publish(StatusChangeEvent.of(
                    DOMAIN_CD, TARGET_TABLE_CD, s.getRschId(),
                    before, RepaymentSchedule.STATUS_SUPERSEDED,
                    REASON_SUPERSEDED_BY_PREPAY,
                    "rtxId=" + saved.getRtxId() + ", newVersion=" + bumpVersion(currentVersion),
                    currentActor.currentActorId()
            ));
        }

        int newCount = 0;
        String newVersion = bumpVersion(currentVersion);
        if (newOutstanding > 0 && !active.isEmpty()) {
            List<EqualPaymentCalculator.Installment> recomputed = EqualPaymentCalculator.calculate(
                    newOutstanding, contract.getTotalRateBps(), active.size());

            List<RepaymentSchedule> toSave = new ArrayList<>(active.size());
            for (int i = 0; i < active.size(); i++) {
                RepaymentSchedule old = active.get(i);
                EqualPaymentCalculator.Installment inst = recomputed.get(i);
                toSave.add(RepaymentSchedule.builder()
                        .cntrId(cntrId)
                        .installmentNo(old.getInstallmentNo())
                        .dueDate(old.getDueDate())
                        .scheduledPrincipal(inst.scheduledPrincipal())
                        .scheduledInterest(inst.scheduledInterest())
                        .scheduledTotal(inst.scheduledTotal())
                        .remainingBalance(inst.remainingBalance())
                        .appliedRateBps(contract.getTotalRateBps())
                        .rschStatusCd(RepaymentSchedule.STATUS_DUE)
                        .rschVersionCd(newVersion)
                        .holidayAdjustedYn(old.getHolidayAdjustedYn())
                        .build());
            }
            scheduleRepository.saveAll(toSave);
            newCount = toSave.size();
        }

        return PrepaymentResponse.of(saved, newOutstanding, active.size(), newVersion, newCount);
    }

    /**
     * 잔여 OVERDUE 회차들의 미수 연체이자 합 — supersede 전에 추가 수금 대상.
     * 회차당: actual = scheduledPrincipal × overdueRateBps × days / 10000 / 365
     *        remaining = max(0, actual - sumPaidOverdueInterestByRschId)
     * 활성 Delinquency 없거나 OVERDUE 회차 없으면 0.
     */
    // package-private: 날짜 경계에서만 틀어지는 계산이라 이 메서드만 떼어 검증한다.
    // 통합 테스트로는 KST 00:00~09:00 이라는 조건을 만들기 어렵다.
    // PrepaymentKstBoundaryTest 참고.
    long computeOverdueInterestSum(Long cntrId, String version, OffsetDateTime now) {
        Optional<Delinquency> activeDlq = delinquencyRepository
                .findByCntrIdAndDlqStatusCdAndDeletedAtIsNull(cntrId, Delinquency.STATUS_ACTIVE);
        if (activeDlq.isEmpty()) return 0L;
        int overdueRateBps = activeDlq.get().getOverdueRateBps();
        if (overdueRateBps <= 0) return 0L;

        List<RepaymentSchedule> activeSchedules = scheduleRepository.findActiveByVersion(cntrId, version);
        LocalDate today = BusinessDate.dateOf(now);
        long sum = 0L;
        for (RepaymentSchedule s : activeSchedules) {
            if (!s.isOverdue()) continue;
            LocalDate dueDate = LocalDate.parse(s.getDueDate(), DATE);
            int days = (int) ChronoUnit.DAYS.between(dueDate, today);
            if (days <= 0) continue;
            long actual = OverdueInterestCalculator.compute(
                    s.getScheduledPrincipal(), overdueRateBps, days);
            long paid = txRepository.sumPaidOverdueInterestByRschId(s.getRschId());
            sum += Math.max(0L, actual - paid);
        }
        return sum;
    }

    /**
     * 중도상환 수수료 산정 — EarlyRepaymentFeePolicy 위임.
     * 잔여 개월 = max(0, contracted_period_mo - elapsed_months),
     * elapsed = ChronoUnit.MONTHS.between(cntr_start_date, today).
     */
    // package-private: 날짜 경계에서만 틀어지는 계산이라 이 메서드만 떼어 검증한다.
    // 통합 테스트로는 KST 00:00~09:00 이라는 조건을 만들기 어렵다.
    // 경과개월이 하루 차이로 한 달 어긋나면 잔여기간이 바뀌어 수수료가 달라진다.
    long computeEarlyRepaymentFee(LoanContract contract, long amount, OffsetDateTime now) {
        LocalDate startDate = LocalDate.parse(contract.getCntrStartDate(), DATE);
        LocalDate today = BusinessDate.dateOf(now);
        long elapsedMonths = ChronoUnit.MONTHS.between(startDate, today);
        int totalMonths = contract.getContractedPeriodMo();
        int remainingMonths = (int) Math.max(0, totalMonths - elapsedMonths);
        return EarlyRepaymentFeePolicy.calculate(amount, totalMonths, remainingMonths);
    }

    private String currentVersionOrInitial(Long cntrId) {
        String max = scheduleRepository.findMaxVersion(cntrId);
        return (max == null || max.isBlank()) ? RepaymentSchedule.VERSION_INITIAL : max;
    }

    /** "V1" → "V2", "V12" → "V13". 파싱 실패 시 V2 로 보정. */
    private String bumpVersion(String current) {
        if (current == null || current.length() < 2 || current.charAt(0) != 'V') {
            return "V2";
        }
        try {
            int n = Integer.parseInt(current.substring(1));
            return "V" + (n + 1);
        } catch (NumberFormatException e) {
            return "V2";
        }
    }

    /**
     * 멱등 재호출 응답에서 outstanding 표기를 위한 보조 계산.
     * 이미 반영된 tx 의 원금까지 포함된 현재 상태를 반환한다.
     */
    private long computeOutstandingExcluding(Long cntrId, RepaymentTransaction alreadyAppliedTx) {
        long paidFromSchedule = scheduleRepository.sumPaidPrincipal(cntrId);
        long paidFromPrepay = txRepository.sumEarlyPrincipal(cntrId);
        long contracted = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .map(LoanContract::getContractedAmount).orElse(0L);
        return Math.max(contracted - paidFromSchedule - paidFromPrepay, 0L);
    }
}
