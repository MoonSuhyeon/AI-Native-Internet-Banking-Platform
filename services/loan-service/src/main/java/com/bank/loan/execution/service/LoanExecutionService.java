package com.bank.loan.execution.service;

import com.bank.common.security.service.ServiceOperation;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.security.crypto.CryptoService;
import com.bank.common.web.BusinessException;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.execution.domain.LoanExecution;
import com.bank.loan.execution.dto.DrawdownRequest;
import com.bank.loan.execution.dto.LoanExecutionResponse;
import com.bank.loan.execution.repository.LoanExecutionRepository;
import com.bank.loan.guaranteeinsurance.domain.GuaranteeInsurance;
import com.bank.loan.guaranteeinsurance.repository.GuaranteeInsuranceRepository;
import com.bank.loan.guarantor.service.GuarantorPolicyValidator;
import com.bank.loan.notification.channel.KafkaChannelAdapter;
import com.bank.loan.notification.channel.StubEmailAdapter;
import com.bank.loan.notification.channel.StubKakaoAdapter;
import com.bank.loan.notification.channel.StubSmsAdapter;
import com.bank.loan.commonsync.outbox.CommonSyncOutboxAppender;
import com.bank.loan.notification.outbox.NotificationOutboxAppender;
import com.bank.loan.payment.SystemAccountProvider;
import com.bank.loan.payment.client.PaymentServiceClient;
import com.bank.loan.payment.client.dto.PaymentRequest;
import com.bank.loan.payment.client.dto.PaymentResponse;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.repaymentaccount.domain.RepaymentAccount;
import com.bank.loan.repaymentaccount.repository.RepaymentAccountRepository;
import com.bank.loan.schedule.service.RepaymentScheduleService;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 자금 인출 (Drawdown) 서비스.
 *
 * 흐름:
 *   1) 멱등성 키가 이미 처리됐으면 기존 응답 그대로 반환
 *   2) 계약 활성 검증 (LOAN_062 / LOAN_063)
 *   2-1) 상환계좌 VERIFIED 검증 (LOAN_080/LOAN_083)
 *   2-2) 보증보험 등록된 계약이면 활성 ISSUED 1건 필요 (LOAN_184)
 *   3) 누적 인출 + 신청 ≤ contracted_amount 검증 (LOAN_064)
 *   4) loan_execution INSERT (status=REQUESTED)
 *   5) payment-service 출금 호출 (흐름 1 — loan-payment-integration-spec)
 *        COMPLETED → markDone, (최초 인출) 계약 ACTIVE + 스케줄 생성 + outbox
 *        FAILED    → markFailed, LOAN_185 예외 (트랜잭션 커밋 보장)
 *        CLEARING  → markFailed, LOAN_185 예외 (미지원)
 */
@Service
@RequiredArgsConstructor
public class LoanExecutionService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_CONTRACT = "LOAN_CONTRACT";
    private static final String DEFAULT_CURRENCY = "KRW";
    private static final String REASON_FIRST_DRAWDOWN = "FIRST_DRAWDOWN";
    private static final String EVENT_LOAN_DISBURSED  = "LOAN_DISBURSED";
    private static final String CHANNEL_OPEN_BANKING = "OPEN_BANKING";

    private final LoanExecutionRepository repository;
    private final LoanContractRepository contractRepository;
    private final LoanApplicationRepository applicationRepository;
    private final LoanProductRepository productRepository;
    private final GuarantorPolicyValidator guarantorPolicyValidator;
    private final RepaymentAccountRepository repaymentAccountRepository;
    private final GuaranteeInsuranceRepository guaranteeInsuranceRepository;
    private final RepaymentScheduleService repaymentScheduleService;
    private final NotificationOutboxAppender outboxAppender;
    private final CommonSyncOutboxAppender commonSyncOutboxAppender;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final CryptoService cryptoService;
    private final PaymentServiceClient paymentServiceClient;
    private final SystemAccountProvider systemAccountProvider;

    // FAILED 기록을 유지하기 위해 BusinessException 발생 시에도 커밋
    @Transactional(noRollbackFor = BusinessException.class)
    public LoanExecutionResponse drawdown(Long cntrId, DrawdownRequest req, String idempotencyKey) {
        // 1) 멱등성
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                long cumul = repository.sumDoneAmount(existing.get().getCntrId());
                return LoanExecutionResponse.of(existing.get(), cumul);
            }
        }

        // 2) 계약 검증
        LoanContract contract = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));
        if (!contract.isDrawdownAllowed()) {
            throw new BusinessException(LoanErrorCode.LOAN_063);
        }

        // 2-1) 상환계좌 사전조건
        RepaymentAccount racct = repaymentAccountRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_080));
        if (!racct.isVerified()) {
            throw new BusinessException(LoanErrorCode.LOAN_083,
                    "racctStatusCd=" + racct.currentStatus());
        }

        // 2-2) 보증보험 사전조건
        validateGuaranteeInsuranceIfApplicable(cntrId);

        // 2-3) 보증 필수 상품이면 활성 SIGNED 보증인 재검증
        applicationRepository.findByApplIdAndDeletedAtIsNull(contract.getApplId()).ifPresent(appl ->
                productRepository.findByProdIdAndDeletedAtIsNull(appl.getProdId()).ifPresent(prod -> {
                    if (!guarantorPolicyValidator.satisfies(appl, prod)) {
                        throw new BusinessException(LoanErrorCode.LOAN_176,
                                "guarantorRequired: signedCount < minGuarantorCount=" + prod.getMinGuarantorCount());
                    }
                })
        );

        // 3) 한도 검증
        long drawnSoFar = repository.sumDoneAmount(contract.getCntrId());
        long requested = req.executedAmount();
        if (drawnSoFar + requested > contract.getContractedAmount()) {
            throw new BusinessException(LoanErrorCode.LOAN_064,
                    "drawnSoFar(" + drawnSoFar + ") + requested(" + requested
                            + ") > contracted(" + contract.getContractedAmount() + ")");
        }

        // 4) 실행 INSERT (REQUESTED) — 이후 payment 결과에 따라 DONE/FAILED 전이
        boolean isFirstDrawdown = LoanContract.STATUS_SIGNED.equals(contract.currentStatus());
        LoanExecution saved = repository.save(LoanExecution.builder()
                .cntrId(contract.getCntrId())
                .transactionId(null)
                .executedAmount(requested)
                .currencyCd(req.currencyCd() == null ? DEFAULT_CURRENCY : req.currencyCd())
                .execStatusCd(LoanExecution.STATUS_REQUESTED)
                .disbursementBankCd(req.disbursementBankCd())
                .disbursementAccountMasked(req.maskedAccount())
                .disbursementAccountEnc(cryptoService.encrypt(req.disbursementAccountNo()))
                .valueDate(req.valueDate())
                .feeAmount(req.feeAmount() == null ? 0L : req.feeAmount())
                .idempotencyKey(idempotencyKey)
                .build());

        // 5) payment-service 출금 호출
        String payIdemKey = "EXEC-" + contract.getCntrId() + "-"
                + (idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : saved.getExecId());
        PaymentRequest payReq = new PaymentRequest(
                ServiceOperation.LOAN_DISBURSE.code(),
                systemAccountProvider.disbursementAccount().getAccountNo(),
                req.disbursementBankCd(),
                req.disbursementAccountNo(),
                contract.getCntrNo(),
                BigDecimal.valueOf(requested),
                "대출실행 " + contract.getCntrNo(),
                "대출실행",
                CHANNEL_OPEN_BANKING,
                contract.getCntrNo()
        );
        PaymentResponse payResp = paymentServiceClient.pay(payIdemKey, payReq);

        if (PaymentResponse.STATUS_COMPLETED.equals(payResp != null ? payResp.status() : null)) {
            String journalEntryNo = "JE-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
            saved.markDone(payResp.paymentInstructionId(), journalEntryNo);

            if (isFirstDrawdown) {
                String before = contract.currentStatus();
                contract.markActiveOnFirstDrawdown();
                statusHistoryPublisher.publish(StatusChangeEvent.of(
                        DOMAIN_CD, TARGET_CONTRACT, contract.getCntrId(),
                        before, LoanContract.STATUS_ACTIVE,
                        REASON_FIRST_DRAWDOWN, "execId=" + saved.getExecId(),
                        currentActor.currentActorId()
                ));
                repaymentScheduleService.generateForFirstDrawdown(contract);

                // 계약 ACTIVE 전이와 동일 트랜잭션에서 common_sync_outbox 적재 (outbox 패턴).
                // 디스패처가 common_contract upsert + loan_contract.contract_id 백필을 비동기 처리.
                commonSyncOutboxAppender.enqueueContractInCurrentTx(contract);

                String payload = String.format(
                        "{\"cntrId\":%d,\"cntrNo\":\"%s\",\"customerId\":%d,\"executedAmount\":%d}",
                        contract.getCntrId(), contract.getCntrNo(), contract.getCustomerId(), requested);
                outboxAppender.enqueueInCurrentTx(EVENT_LOAN_DISBURSED, contract.getCntrId(), KafkaChannelAdapter.CHANNEL_CD, payload);
                outboxAppender.enqueueInCurrentTx(EVENT_LOAN_DISBURSED, contract.getCntrId(), StubSmsAdapter.CHANNEL_CD, payload);
                outboxAppender.enqueueInCurrentTx(EVENT_LOAN_DISBURSED, contract.getCntrId(), StubKakaoAdapter.CHANNEL_CD, payload);
                outboxAppender.enqueueInCurrentTx(EVENT_LOAN_DISBURSED, contract.getCntrId(), StubEmailAdapter.CHANNEL_CD, payload);
            }

            long cumul = drawnSoFar + requested;
            return LoanExecutionResponse.of(saved, cumul);
        }

        // FAILED 또는 CLEARING — FAILED 기록 후 예외 (noRollbackFor 로 커밋됨)
        String piId = payResp != null ? payResp.paymentInstructionId() : null;
        String failDetail = payResp != null
                ? "status=" + payResp.status() + ", failureCategory=" + payResp.failureCategory()
                : "payment-service 응답 없음";
        saved.markFailed(piId);
        throw new BusinessException(LoanErrorCode.LOAN_185, failDetail);
    }

    private void validateGuaranteeInsuranceIfApplicable(Long cntrId) {
        if (!guaranteeInsuranceRepository.existsByCntrIdAndDeletedAtIsNull(cntrId)) {
            return;
        }
        if (!guaranteeInsuranceRepository.existsByCntrIdAndGinsStatusCdAndDeletedAtIsNull(
                cntrId, GuaranteeInsurance.STATUS_ISSUED)) {
            throw new BusinessException(LoanErrorCode.LOAN_184, "cntrId=" + cntrId);
        }
    }
}
