package com.bank.deposit.dto.request;

import java.math.BigDecimal;

public record BankingProductRequest(
        String bankingProductType,
        BigDecimal minJoinAmount,
        BigDecimal maxJoinAmount,
        Integer minContractPeriodMonth,
        Integer maxContractPeriodMonth,
        Boolean autoTransferAvailableYn,
        Boolean autoRenewalAvailableYn
) {}
