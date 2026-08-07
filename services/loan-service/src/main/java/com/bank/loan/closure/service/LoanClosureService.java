package com.bank.loan.closure.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.closure.domain.LoanClosure;
import com.bank.loan.closure.dto.CloseLoanRequest;
import com.bank.loan.closure.dto.LoanClosureResponse;
import com.bank.loan.closure.repository.LoanClosureRepository;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.repayment.repository.RepaymentTransactionRepository;
import com.bank.loan.schedule.repository.RepaymentScheduleRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 약정 종결 서비스 — flows §1.1 DISBURSED → CLOSED.
 *
 * 흐름:
 *   1) 계약 ACTIVE 검증 (LOAN_120)
 *   2) 이미 종결된 계약 차단 (LOAN_123)
 *   3) NORMAL/EARLY 면 잔액=0 (LOAN_121) + 활성 회차 없음 (LOAN_122)
 *      WRITE_OFF/SUBROGATION 은 잔액 검증 면제 (사고 종결)
 *   4) 정산 산출:
 *        final_principal = Σ(PAID 회차 scheduled_principal)
 *        final_interest  = Σ(SUCCESS RepaymentTransaction.interest_amount)
 *        prepayment_fee  = req.prepaymentFeeAmt or 0
 *        final_fee       = req.finalFeeAmt or 0
 *        total_settled   = principal + interest + fee + prepayment
 *   5) LOAN_CLOSURE INSERT (status=COMPLETED, closed_at=now)
 *   6) LoanContract → STATUS_CLOSED + status_history (reason=CLOSURE_COMPLETED)
 *
 * 본 단계: REQUESTED → 별도 결재 워크플로우 미지원, 단일 COMPLETED 트랜잭션.
 */
@Service
@RequiredArgsConstructor
public class LoanClosureService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_CONTRACT = "LOAN_CONTRACT";
    private static final String REASON_CLOSURE_COMPLETED = "CLOSURE_COMPLETED";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoanClosureRepository repository;
    private final LoanContractRepository contractRepository;
    private final RepaymentScheduleRepository scheduleRepository;
    private final RepaymentTransactionRepository txRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;

    @Transactional
    public LoanClosureResponse close(Long cntrId, CloseLoanRequest req) {
        LoanContract contract = contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        if (contract.isClosed()) {
            throw new BusinessException(LoanErrorCode.LOAN_123);
        }
        if (!contract.isClosable()) {
            throw new BusinessException(LoanErrorCode.LOAN_120,
                    "current=" + contract.currentStatus());
        }
        if (repository.findByCntrIdAndDeletedAtIsNull(cntrId).isPresent()) {
            throw new BusinessException(LoanErrorCode.LOAN_123);
        }

        long paidPrincipal = scheduleRepository.sumPaidPrincipal(cntrId);
        long paidInterest = txRepository.sumInterestAmount(cntrId);
        long remaining = contract.getContractedAmount() - paidPrincipal;

        if (LoanClosure.requiresZeroBalance(req.closureTypeCd())) {
            if (remaining != 0L) {
                throw new BusinessException(LoanErrorCode.LOAN_121,
                        "remainingPrincipal=" + remaining);
            }
            if (scheduleRepository.existsActiveInstallment(cntrId)) {
                throw new BusinessException(LoanErrorCode.LOAN_122);
            }
        }

        long prepaymentFee = req.prepaymentFeeAmt() == null ? 0L : req.prepaymentFeeAmt();
        long finalFee = req.finalFeeAmt() == null ? 0L : req.finalFeeAmt();
        long totalSettled = paidPrincipal + paidInterest + finalFee + prepaymentFee;

        OffsetDateTime now = OffsetDateTime.now();
        String closDate = req.closureDate() == null
                ? BusinessDate.today()
                : req.closureDate();

        LoanClosure saved = repository.save(LoanClosure.builder()
                .cntrId(cntrId)
                .closTypeCd(req.closureTypeCd())
                .closReasonCd(req.closureReasonCd())
                .closStatusCd(LoanClosure.STATUS_COMPLETED)
                .finalPrincipalAmt(paidPrincipal)
                .finalInterestAmt(paidInterest)
                .finalFeeAmt(finalFee)
                .prepaymentFeeAmt(prepaymentFee)
                .totalSettledAmt(totalSettled)
                .closDate(closDate)
                .closedAt(now)
                .closDocUrl(req.closureDocUrl())
                .closDocHash(req.closureDocHash())
                .build());

        String beforeStatus = contract.currentStatus();
        contract.markClosed();
        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_CONTRACT, cntrId,
                beforeStatus, LoanContract.STATUS_CLOSED,
                REASON_CLOSURE_COMPLETED,
                "closId=" + saved.getClosId()
                        + " / type=" + req.closureTypeCd(),
                currentActor.currentActorId()
        ));

        return LoanClosureResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public LoanClosureResponse get(Long cntrId) {
        contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));
        return repository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .map(LoanClosureResponse::of)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_124));
    }
}
