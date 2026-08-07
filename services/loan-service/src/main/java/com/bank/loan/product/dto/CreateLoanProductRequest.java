package com.bank.loan.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateLoanProductRequest(

        @NotBlank @Size(max = 30) String prodCd,
        @NotBlank @Size(max = 200) String prodName,

        @NotBlank @Size(max = 50) String loanTypeCd,
        @Size(max = 50) String targetCustomerCd,
        @NotBlank @Size(max = 50) String repaymentMethodCd,
        @NotBlank @Size(max = 50) String rateTypeCd,

        @NotNull @Min(0) Integer baseRateBps,
        @Min(0) Integer minRateBps,
        @Min(0) Integer maxRateBps,

        @NotNull @Min(0) Long minAmount,
        @NotNull @Min(0) Long maxAmount,
        @NotNull @Min(1) Integer minPeriodMo,
        @NotNull @Min(1) Integer maxPeriodMo,

        Boolean collateralRequiredYn,
        Boolean guarantorRequiredYn,
        @Min(0) Integer minGuarantorCount,

        @Min(1) Integer applicationValidityDays,

        @Pattern(regexp = "\\d{8}") String saleStartDate,
        @Pattern(regexp = "\\d{8}") String saleEndDate,

        @Size(max = 500) String prodTermsUrl,
        @Size(max = 128) String prodTermsHash,

        Long productId
) {
}
