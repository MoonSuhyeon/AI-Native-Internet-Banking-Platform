package com.bank.loan.repaymentaccount.dto;

import com.bank.loan.repaymentaccount.domain.RepaymentAccount;

import java.time.OffsetDateTime;

public record RepaymentAccountResponse(
        Long racctId,
        Long cntrId,
        Long accountId,
        String bankCd,
        String accountNoMasked,
        String holderNameMasked,
        String racctStatusCd,
        Boolean autoDebitYn,
        Integer debitDay,
        OffsetDateTime verifiedAt
) {
    public static RepaymentAccountResponse of(RepaymentAccount r) {
        return new RepaymentAccountResponse(
                r.getRacctId(), r.getCntrId(), r.getAccountId(),
                r.getBankCd(), r.getAccountNoMasked(), r.getHolderNameMasked(),
                r.getRacctStatusCd(), r.getAutoDebitYn(), r.getDebitDay(),
                r.getVerifiedAt()
        );
    }
}
