package com.bank.loan.product.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 상품 부분 수정 요청. 모든 필드 optional — null 인 필드는 변경하지 않는다.
 * 상품 코드(prodCd) · 식별자(prodId, productId) 는 수정 대상에서 제외.
 */
public record UpdateLoanProductRequest(

        @Size(max = 200) String prodName,

        @Size(max = 50) String loanTypeCd,
        @Size(max = 50) String targetCustomerCd,
        @Size(max = 50) String repaymentMethodCd,
        @Size(max = 50) String rateTypeCd,

        @Min(0) Integer baseRateBps,
        @Min(0) Integer minRateBps,
        @Min(0) Integer maxRateBps,

        @Min(0) Long minAmount,
        @Min(0) Long maxAmount,
        @Min(1) Integer minPeriodMo,
        @Min(1) Integer maxPeriodMo,

        Boolean collateralRequiredYn,
        Boolean guarantorRequiredYn,
        @Min(0) Integer minGuarantorCount,

        @Min(1) Integer applicationValidityDays,

        @Pattern(regexp = "\\d{8}") String saleStartDate,
        @Pattern(regexp = "\\d{8}") String saleEndDate,

        @Size(max = 500) String prodTermsUrl,
        @Size(max = 128) String prodTermsHash,

        @Size(max = 50) String prodStatusCd
) {
}
