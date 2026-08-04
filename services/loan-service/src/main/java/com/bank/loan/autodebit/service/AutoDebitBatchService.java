package com.bank.loan.autodebit.service;

import com.bank.loan.autodebit.domain.AutoDebitClearingPending;
import com.bank.loan.autodebit.dto.AutoDebitRunResponse;
import com.bank.loan.autodebit.repository.AutoDebitClearingPendingRepository;
import com.bank.loan.calendar.service.BusinessDayService;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.common.security.crypto.CryptoService;
import com.bank.commonaccount.domain.CommonAccount;
import com.bank.loan.payment.SystemAccountProvider;
import com.bank.loan.payment.client.PaymentServiceClient;
import com.bank.loan.payment.client.dto.PaymentRequest;
import com.bank.loan.payment.client.dto.PaymentResponse;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.dto.RepayInstallmentRequest;
import com.bank.loan.repayment.service.RepaymentService;
import com.bank.loan.repaymentaccount.domain.RepaymentAccount;
import com.bank.loan.repaymentaccount.repository.RepaymentAccountRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 자동이체 배치 (flows §2.2).
 *
 * 실행 시점: 매일 새벽 (별도 스케줄러). 본 단계는 운영자가 baseDate 를 지정해 수동 호출.
 *
 * 처리 대상 (lookup 범위):
 *   REPAYMENT_SCHEDULE.due_date ∈ ( lastBusinessDayBefore(baseDate), baseDate ]
 *   AND rsch_status_cd = DUE AND rsch_version_cd = V1
 *   AND 해당 계약의 REPAYMENT_ACCOUNT.auto_debit_yn = Y AND racct_status_cd = VERIFIED
 *
 * 신규 약정(V8 이후) 은 스케줄 생성 시 휴일 보정으로 dueDate 가 baseDate 와 정확히 일치한다.
 * 구약정의 비영업일 dueDate (예: 토/일 회차) 는 직전 영업일 다음날부터 baseDate 까지의 구간으로 흡수되어
 * 익영업일 배치에서 처리된다 (plan 05).
 *
 * 흐름:
 *   1. 출금 대상 회차 조회 (DUE/OVERDUE, auto_debit_yn=Y, racct_status=VERIFIED)
 *   2. repayment_account.account_no_enc 복호화 → 실제 계좌번호
 *   3. payment-service POST /api/v1/payments 호출
 *       COMPLETED → repaymentService.repayInstallment() → STATUS_SUCCESS
 *       FAILED    → RepaymentTransaction STATUS_FAILED 기록
 *       CLEARING  → auto_debit_clearing_pending 등록, payment.* Kafka 이벤트로 완결 (PaymentEventConsumer)
 *
 * 멱등성: 회차당 idempotency_key = "AUTO-{cntrId}-{rschId}-{baseDate}" 자체 채번.
 * 같은 baseDate 재실행 시 RepaymentTransaction.idempotency_key UNIQUE 제약으로 중복 출금 차단.
 *
 * 휴일 보정(BUSINESS_CALENDAR): 호출 시 baseDate 가 비영업일이면 출금을 수행하지 않고 skipReason=NON_BUSINESS_DAY
 * 로 즉시 반환 (flows §2.2 — 휴일에는 INTEREST_ACCRUAL 만 발생, 출금은 익영업일로 이월).
 */
@Service
@RequiredArgsConstructor
public class AutoDebitBatchService {

    private static final Logger log = LoggerFactory.getLogger(AutoDebitBatchService.class);

    // 원장 기록용 채널 (RepaymentTransaction.channel_cd) — 자동이체 식별값
    // 원장(repayment_transaction.channel_cd) 자동이체 식별값.
    // 회계 요약 집계(AccountingSummaryQuery)와 시드(V39)가 AUTO_DEBIT 을 정본으로 쓴다.
    // 과거 INBOUND 로 적재하던 탓에 일일·월별 회계 요약의 자동이체 건수가 0 이었다.
    // payment-service 호출 채널(OPEN_BANKING)과는 별개 필드다.
    private static final String CHANNEL_AUTO_DEBIT = "AUTO_DEBIT";
    // payment-service 호출 채널 CHECK: WEB/MOBILE/BRANCH/ATM/OPEN_BANKING (INBOUND은 타행→당행 수신 전용이라 OUT 이체엔 사용 불가)
    private static final String PAYMENT_CHANNEL_OPEN_BANKING = "OPEN_BANKING";

    private final RepaymentScheduleRepository scheduleRepository;
    private final RepaymentAccountRepository repaymentAccountRepository;
    private final LoanContractRepository contractRepository;
    private final RepaymentService repaymentService;
    private final PaymentServiceClient paymentServiceClient;
    private final SystemAccountProvider systemAccountProvider;
    private final CryptoService cryptoService;
    private final BusinessDayService businessDayService;
    private final AutoDebitClearingPendingRepository clearingPendingRepository;

    public AutoDebitRunResponse run(String baseDate) {
        if (!businessDayService.isBusinessDay(baseDate)) {
            log.info("auto-debit skipped: baseDate={} is non-business day", baseDate);
            return AutoDebitRunResponse.skippedNonBusinessDay(baseDate);
        }

        String lastBusinessDay = businessDayService.lastBusinessDayBefore(baseDate);
        List<RepaymentSchedule> candidates = scheduleRepository
                .findDueOrPostponedForAutoDebit(
                        lastBusinessDay, baseDate,
                        RepaymentSchedule.STATUS_DUE, RepaymentSchedule.VERSION_INITIAL);

        int processed = 0;
        int skipped = 0;

        for (RepaymentSchedule schedule : candidates) {
            Optional<RepaymentAccount> accountOpt = findEligibleAccount(schedule.getCntrId());
            if (accountOpt.isEmpty()) {
                skipped++;
                continue;
            }
            RepaymentAccount account = accountOpt.get();

            Optional<LoanContract> contractOpt = contractRepository
                    .findByCntrIdAndDeletedAtIsNull(schedule.getCntrId());
            if (contractOpt.isEmpty()) {
                log.warn("auto-debit: contract not found for cntrId={}", schedule.getCntrId());
                skipped++;
                continue;
            }
            LoanContract contract = contractOpt.get();

            String idemKey = buildIdempotencyKey(schedule, baseDate);
            RepayInstallmentRequest req = new RepayInstallmentRequest(
                    schedule.getInstallmentNo(), CHANNEL_AUTO_DEBIT, baseDate);

            try {
                PaymentRequest payReq = buildPaymentRequest(schedule, account, contract);
                PaymentResponse payResp = paymentServiceClient.pay(idemKey, payReq);

                if (payResp == null) {
                    log.warn("auto-debit: null response from payment-service cntrId={}", schedule.getCntrId());
                    skipped++;
                    continue;
                }

                String piId = payResp.paymentInstructionId();
                if (PaymentResponse.STATUS_COMPLETED.equals(payResp.status())) {
                    repaymentService.repayInstallment(schedule.getCntrId(), req, idemKey, null, piId);
                    processed++;
                } else if (PaymentResponse.STATUS_FAILED.equals(payResp.status())) {
                    log.warn("auto-debit: payment failed cntrId={} installmentNo={} failureCategory={}",
                            schedule.getCntrId(), schedule.getInstallmentNo(), payResp.failureCategory());
                    repaymentService.repayInstallment(schedule.getCntrId(), req, idemKey,
                            RepaymentTransaction.STATUS_FAILED, piId);
                    skipped++;
                } else if (PaymentResponse.STATUS_CLEARING.equals(payResp.status())) {
                    // 타행 청산 대기: payment 가 정산 완료 후 payment.completed/failed Kafka 이벤트 발행.
                    // 이벤트엔 piId 만 실려오므로 piId↔회차 매핑을 저장해 두고 PaymentEventConsumer 가 완결한다.
                    if (clearingPendingRepository.findByPiId(piId).isEmpty()) {
                        clearingPendingRepository.save(AutoDebitClearingPending.of(
                                piId, schedule.getCntrId(), schedule.getRschId(),
                                schedule.getInstallmentNo(), baseDate, idemKey));
                    }
                    log.info("auto-debit: CLEARING 대기 등록 cntrId={} installmentNo={} piId={}",
                            schedule.getCntrId(), schedule.getInstallmentNo(), piId);
                    skipped++;
                } else {
                    log.warn("auto-debit: unknown payment status={} cntrId={} piId={}",
                            payResp.status(), schedule.getCntrId(), piId);
                    skipped++;
                }
            } catch (RuntimeException e) {
                log.warn("auto-debit failed for cntrId={} installmentNo={} baseDate={}: {}",
                        schedule.getCntrId(), schedule.getInstallmentNo(), baseDate, e.toString());
                skipped++;
            }
        }

        return AutoDebitRunResponse.of(baseDate, candidates.size(), processed, skipped);
    }

    private Optional<RepaymentAccount> findEligibleAccount(Long cntrId) {
        return repaymentAccountRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .filter(a -> a.isVerified() && RepaymentAccount.YN_Y.equals(a.getAutoDebitYn()));
    }

    private PaymentRequest buildPaymentRequest(RepaymentSchedule schedule,
                                               RepaymentAccount account,
                                               LoanContract contract) {
        String senderAccountNo = cryptoService.decrypt(account.getAccountNoEnc());
        CommonAccount collection = systemAccountProvider.collectionAccount();
        return new PaymentRequest(
                senderAccountNo,
                collection.getBankCd(),
                collection.getAccountNo(),
                collection.getAccountNickname(),
                BigDecimal.valueOf(schedule.getScheduledTotal()),
                "대출상환 " + schedule.getInstallmentNo() + "회차",
                "대출상환",
                PAYMENT_CHANNEL_OPEN_BANKING,
                contract.getCntrNo()
        );
    }

    private String buildIdempotencyKey(RepaymentSchedule schedule, String baseDate) {
        return "AUTO-" + schedule.getCntrId() + "-" + schedule.getRschId() + "-" + baseDate;
    }
}
