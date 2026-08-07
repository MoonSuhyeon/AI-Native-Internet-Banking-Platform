package com.bank.loan.repaymentaccount.dto;

import com.bank.common.security.mask.Masking;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRepaymentAccountRequest(

        @NotBlank @Size(max = 10) String bankCd,

        @NotBlank @Size(max = 50) String accountNo,

        @Size(max = 50) String holderName,

        Long accountId,

        Boolean autoDebitYn,

        @Min(1) @Max(31) Integer debitDay
) {
    public String maskedAccount() {
        return Masking.account(accountNo);
    }

    public String maskedHolderName() {
        return Masking.name(holderName);
    }
}
