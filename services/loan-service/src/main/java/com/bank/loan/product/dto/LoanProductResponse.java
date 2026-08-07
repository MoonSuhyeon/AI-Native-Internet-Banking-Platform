package com.bank.loan.product.dto;

import com.bank.loan.product.domain.LoanProduct;

public record LoanProductResponse(
        Long prodId,
        String prodCd,
        String prodName,
        String loanTypeCd,
        String targetCustomerCd,
        String repaymentMethodCd,
        String rateTypeCd,
        Integer baseRateBps,
        Integer minRateBps,
        Integer maxRateBps,
        Long minAmount,
        Long maxAmount,
        Integer minPeriodMo,
        Integer maxPeriodMo,
        Boolean collateralRequiredYn,
        Boolean guarantorRequiredYn,
        Integer minGuarantorCount,
        Integer applicationValidityDays,
        String saleStartDate,
        String saleEndDate,
        String prodStatusCd,
        String prodTermsUrl,
        String prodTermsHash,
        Long productId
) {
    public static LoanProductResponse of(LoanProduct p) {
        return new LoanProductResponse(
                p.getProdId(), p.getProdCd(), p.getProdName(),
                p.getLoanTypeCd(), p.getTargetCustomerCd(),
                p.getRepaymentMethodCd(), p.getRateTypeCd(),
                p.getBaseRateBps(), p.getMinRateBps(), p.getMaxRateBps(),
                p.getMinAmount(), p.getMaxAmount(),
                p.getMinPeriodMo(), p.getMaxPeriodMo(),
                p.getCollateralRequiredYn(), p.getGuarantorRequiredYn(),
                p.getMinGuarantorCount(), p.getApplicationValidityDays(),
                p.getSaleStartDate(), p.getSaleEndDate(),
                p.getProdStatusCd(), p.getProdTermsUrl(), p.getProdTermsHash(),
                p.getProductId()
        );
    }
}
