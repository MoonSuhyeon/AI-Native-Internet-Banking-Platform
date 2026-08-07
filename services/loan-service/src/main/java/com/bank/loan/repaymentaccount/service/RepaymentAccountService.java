package com.bank.loan.repaymentaccount.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.security.crypto.CryptoService;
import com.bank.common.web.BusinessException;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.repaymentaccount.domain.RepaymentAccount;
import com.bank.loan.repaymentaccount.dto.RegisterRepaymentAccountRequest;
import com.bank.loan.repaymentaccount.dto.RepaymentAccountResponse;
import com.bank.loan.repaymentaccount.dto.VerifyRepaymentAccountRequest;
import com.bank.loan.repaymentaccount.repository.RepaymentAccountRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 상환계좌 등록·검증 서비스.
 *
 * 흐름:
 *   register : 계약 존재 검증 → 중복 등록 차단(cntr_id UNIQUE) → INSERT(status=REGISTERED)
 *   verify   : 상환계좌 존재 검증 → REGISTERED 상태 검증 → status=VERIFIED + verified_at
 *
 * 외부 계좌검증(예금주 실명조회 등) 은 stub — 본 verify 호출 자체가 성공으로 간주된다.
 * 실제 외부 연계 도입 시 verify 내부에서 호출하고 결과를 verifyRemark 에 기록한다.
 */
@Service
@RequiredArgsConstructor
public class RepaymentAccountService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "REPAYMENT_ACCOUNT";
    private static final String REASON_REGISTERED = "ACCOUNT_REGISTERED";
    private static final String REASON_VERIFIED = "ACCOUNT_VERIFIED";

    private final RepaymentAccountRepository repository;
    private final LoanContractRepository contractRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final CryptoService cryptoService;

    @Transactional
    public RepaymentAccountResponse register(Long cntrId, RegisterRepaymentAccountRequest req) {
        contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));

        repository.findByCntrIdAndDeletedAtIsNull(cntrId).ifPresent(r -> {
            throw new BusinessException(LoanErrorCode.LOAN_081);
        });

        RepaymentAccount saved = repository.save(RepaymentAccount.builder()
                .cntrId(cntrId)
                .accountId(req.accountId())
                .bankCd(req.bankCd())
                .accountNoMasked(req.maskedAccount())
                .accountNoEnc(cryptoService.encrypt(req.accountNo()))
                .holderNameMasked(req.maskedHolderName())
                .holderNameEnc(req.holderName() != null ? cryptoService.encrypt(req.holderName()) : null)
                .racctStatusCd(RepaymentAccount.STATUS_REGISTERED)
                .autoDebitYn(req.autoDebitYn() == null ? false : req.autoDebitYn())
                .debitDay(req.debitDay())
                .build());

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, saved.getRacctId(),
                null, RepaymentAccount.STATUS_REGISTERED,
                REASON_REGISTERED, "cntrId=" + cntrId,
                currentActor.currentActorId()
        ));

        return RepaymentAccountResponse.of(saved);
    }

    @Transactional
    public RepaymentAccountResponse verify(Long cntrId, VerifyRepaymentAccountRequest req) {
        RepaymentAccount account = repository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_080));

        if (!RepaymentAccount.STATUS_REGISTERED.equals(account.currentStatus())) {
            throw new BusinessException(LoanErrorCode.LOAN_082,
                    "current=" + account.currentStatus());
        }

        String before = account.currentStatus();
        account.markVerified(OffsetDateTime.now());

        String remark = buildVerifyRemark(req);
        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, account.getRacctId(),
                before, RepaymentAccount.STATUS_VERIFIED,
                REASON_VERIFIED, remark,
                currentActor.currentActorId()
        ));

        return RepaymentAccountResponse.of(account);
    }

    @Transactional(readOnly = true)
    public RepaymentAccountResponse get(Long cntrId) {
        return repository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .map(RepaymentAccountResponse::of)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_080));
    }

    private String buildVerifyRemark(VerifyRepaymentAccountRequest req) {
        if (req == null) return null;
        String channel = req.verifyChannelCd();
        String memo = req.verifyRemark();
        if (channel == null && memo == null) return null;
        if (channel == null) return memo;
        if (memo == null) return "channel=" + channel;
        return "channel=" + channel + " / " + memo;
    }
}
