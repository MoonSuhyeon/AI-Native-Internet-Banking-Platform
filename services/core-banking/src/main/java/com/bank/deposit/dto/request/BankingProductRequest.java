package com.bank.deposit.dto.request;

import java.math.BigDecimal;

public record BankingProductRequest(
        String bankingProductType,
        Long minJoinAmount,
        Long maxJoinAmount,
        Integer minContractPeriodMonth,
        Integer maxContractPeriodMonth,
        Boolean autoTransferAvailableYn,
        Boolean autoRenewalAvailableYn
) {}
