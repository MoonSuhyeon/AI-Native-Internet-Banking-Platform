package com.bank.loan.repayment.service;

import com.bank.common.security.service.ServiceOperation;
import com.bank.common.security.crypto.CryptoService;
import com.bank.common.web.BusinessException;
import com.bank.commonaccount.domain.CommonAccount;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.payment.SystemAccountProvider;
import com.bank.loan.payment.client.PaymentServiceClient;
import com.bank.loan.payment.client.dto.PaymentRequest;
import com.bank.loan.payment.client.dto.PaymentResponse;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.dto.RepayInstallmentRequest;
import com.bank.loan.repayment.dto.RepaymentTransactionResponse;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.repaymentaccount.domain.RepaymentAccount;
import com.bank.loan.repaymentaccount.repository.RepaymentAccountRepository;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 온라인(WEB·MOBILE) 상환 서비스 — payment-service PULL 방식.
 *
 * 흐름:
 *   1) 멱등성 검사 — 동일 payIdemKey 로 기존 tx 존재 시 즉시 반환
 *   2) 계약 존재 확인
 *   3) 상환계좌 VERIFIED 확인
 *   4) 스케줄 DUE/OVERDUE 확인
 *   5) PaymentServiceClient.pay() — 고객 상환계좌 → 은행 수납계좌
 *      COMPLETED → repaymentService.repayInstallment(SUCCESS 경로)
 *      FAILED    → repaymentService.repayInstallment(FAILED 경로) + LOAN_187
 *      CLEARING/기타 → LOAN_187 (미지원)
 *
 * 멱등키: "ONL-{cntrId}-{rschId}-{callerIdempotencyKey}" (미제공 시 suffix 없음)
 * noRollbackFor=BusinessException — FAILED tx 는 반드시 커밋.
 */
@Service
@RequiredArgsConstructor
public class OnlineRepaymentService {

    private static final String IDEM_PREFIX = "ONL";
    private static final String DEFAULT_CHANNEL = "WEB";

    private final RepaymentTransactionRepository txRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final RepaymentAccountRepository repaymentAccountRepository;
    private final LoanContractRepository contractRepository;
    private final RepaymentService repaymentService;
    private final PaymentServiceClient paymentServiceClient;
    private final SystemAccountProvider systemAccountProvider;
    private final CryptoService cryptoService;

    @Transactional(noRollbackFor = BusinessException.class)
    public RepaymentTransactionResponse repayOnline(Long cntrId, RepayInstallmentRequest req,
                                                    String idempotencyKey) {
        // 1) 계약·상환계좌·스케줄 조회 (payment idemKey 구성에 rschId 필요)
        contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        RepaymentAccount account = repaymentAccountRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_080));
        if (!account.isVerified()) {
            throw new BusinessException(LoanErrorCode.LOAN_083);
        }

        RepaymentSchedule schedule = scheduleRepository
                .findByCntrIdAndInstallmentNoAndRschVersionCdAndDeletedAtIsNull(
                        cntrId, req.installmentNo(), RepaymentSchedule.VERSION_INITIAL)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_090,
                        "cntrId=" + cntrId + ", installmentNo=" + req.installmentNo()));
        // 2) 멱등성 — payIdemKey 로 기존 tx 확인
        //
        // 회차 상태 검사보다 먼저 한다. 상환이 성공하면 회차가 PAID 로 바뀌므로,
        // 상태 검사를 앞에 두면 같은 Idempotency-Key 로 재시도해도 원래 결과 대신
        // LOAN_091(409) 이 돌아간다. 응답을 못 받고 재시도하는 상황이 멱등성이
        // 존재하는 이유이므로, 그 경로가 막히면 멱등성이 무력화된다.
        String payIdemKey = buildPayIdemKey(cntrId, schedule.getRschId(), idempotencyKey);
        var existing = txRepository.findByIdempotencyKey(payIdemKey);
        if (existing.isPresent()) {
            RepaymentTransaction tx = existing.get();
            if (RepaymentTransaction.STATUS_FAILED.equals(tx.getRtxStatusCd())) {
                throw new BusinessException(LoanErrorCode.LOAN_187,
                        "이전 결제 시도가 실패했습니다. 새 Idempotency-Key 로 재시도하세요.");
            }
            return RepaymentTransactionResponse.of(tx);
        }

        // 3) 회차 상태 검사 — 멱등 재시도가 아닌데 이미 납부된 경우
        if (!schedule.isPayable()) {
            throw new BusinessException(LoanErrorCode.LOAN_091, "current=" + schedule.currentStatus());
        }

        // 3) 계약번호 조회 (수취인 통장 표시용)
        LoanContract contract = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        // 4) payment-service 호출
        String channelCd = (req.channelCd() != null && !req.channelCd().isBlank())
                ? req.channelCd() : DEFAULT_CHANNEL;

        PaymentRequest payReq = buildPaymentRequest(schedule, account, contract, channelCd);
        PaymentResponse payResp = paymentServiceClient.pay(payIdemKey, payReq);

        if (payResp == null) {
            throw new BusinessException(LoanErrorCode.LOAN_187, "payment-service 응답 없음");
        }

        String piId = payResp.paymentInstructionId();
        RepayInstallmentRequest repayReq = new RepayInstallmentRequest(
                req.installmentNo(), channelCd, req.valueDate());

        if (PaymentResponse.STATUS_COMPLETED.equals(payResp.status())) {
            return repaymentService.repayInstallment(cntrId, repayReq, payIdemKey, null, piId);
        }

        if (PaymentResponse.STATUS_FAILED.equals(payResp.status())) {
            repaymentService.repayInstallment(cntrId, repayReq, payIdemKey,
                    RepaymentTransaction.STATUS_FAILED, piId);
            throw new BusinessException(LoanErrorCode.LOAN_187,
                    "failureCategory=" + payResp.failureCategory());
        }

        // CLEARING 또는 알 수 없는 상태 — 미지원
        throw new BusinessException(LoanErrorCode.LOAN_187,
                "status=" + payResp.status() + " (CLEARING/온라인 상환 미지원)");
    }

    private String buildPayIdemKey(Long cntrId, Long rschId, String idempotencyKey) {
        String base = IDEM_PREFIX + "-" + cntrId + "-" + rschId;
        return (idempotencyKey != null && !idempotencyKey.isBlank())
                ? base + "-" + idempotencyKey
                : base;
    }

    private PaymentRequest buildPaymentRequest(RepaymentSchedule schedule,
                                               RepaymentAccount account,
                                               LoanContract contract,
                                               String channelCd) {
        String senderAccountNo = cryptoService.decrypt(account.getAccountNoEnc());
        CommonAccount collection = systemAccountProvider.collectionAccount();
        return new PaymentRequest(
                ServiceOperation.LOAN_REPAY.code(),
                senderAccountNo,
                collection.getBankCd(),
                collection.getAccountNo(),
                collection.getAccountNickname(),
                BigDecimal.valueOf(schedule.getScheduledTotal()),
                "대출상환 " + schedule.getInstallmentNo() + "회차",
                "대출상환",
                channelCd,
                contract.getCntrNo()
        );
    }
}
