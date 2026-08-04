package com.bank.loan.autodebit.service;

import com.bank.common.web.BusinessException;
import com.bank.loan.autodebit.dto.AutoDebitPaymentResultRequest;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.dto.RepayInstallmentRequest;
import com.bank.loan.repayment.dto.RepaymentTransactionResponse;
import com.bank.loan.repayment.service.RepaymentService;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * payment-service CLEARING 완결 콜백 처리.
 *
 * 멱등키("AUTO-{cntrId}-{rschId}-{baseDate}") 를 파싱해 상환 처리를 완결하거나
 * 실패 트랜잭션을 기록한다.
 *
 * 이미 처리된 멱등키는 RepaymentService 내부 idempotency check 로 차단된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoDebitCallbackService {

    private final RepaymentService repaymentService;
    private final RepaymentScheduleRepository scheduleRepository;

    public RepaymentTransactionResponse handleResult(AutoDebitPaymentResultRequest req) {
        // idempotencyKey: "AUTO-{cntrId}-{rschId}-{baseDate}"
        String[] parts = req.idempotencyKey().split("-");
        if (parts.length != 4 || !"AUTO".equals(parts[0])) {
            throw new BusinessException(LoanErrorCode.LOAN_090,
                    "invalid idempotencyKey format: " + req.idempotencyKey());
        }

        long cntrId;
        long rschId;
        String baseDate;
        try {
            cntrId   = Long.parseLong(parts[1]);
            rschId   = Long.parseLong(parts[2]);
            baseDate = parts[3];
        } catch (NumberFormatException e) {
            throw new BusinessException(LoanErrorCode.LOAN_090,
                    "invalid idempotencyKey parts: " + req.idempotencyKey());
        }

        RepaymentSchedule schedule = scheduleRepository.findById(rschId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_090,
                        "rschId=" + rschId));

        RepayInstallmentRequest installmentReq = new RepayInstallmentRequest(
                schedule.getInstallmentNo(),
                // 원장 채널 정본 — AccountingSummaryQuery·V39 시드와 일치시킨다.
                "AUTO_DEBIT",
                baseDate
        );

        String paymentStatus = "COMPLETED".equals(req.status()) ? null : RepaymentTransaction.STATUS_FAILED;

        log.info("auto-debit callback: piId={} status={} cntrId={} rschId={}",
                req.piId(), req.status(), cntrId, rschId);

        return repaymentService.repayInstallment(cntrId, installmentReq,
                req.idempotencyKey(), paymentStatus, req.piId());
    }
}
