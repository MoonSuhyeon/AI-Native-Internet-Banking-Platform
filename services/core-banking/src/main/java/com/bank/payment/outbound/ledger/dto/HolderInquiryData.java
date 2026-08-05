package com.bank.payment.outbound.feign.dto;

public record HolderInquiryData(
        String accountNo,
        String holderName,
        String holderType,      // INDIVIDUAL / CORPORATE / JOINT
        String customerId,
        Boolean deceasedFlag,
        Integer version
) {}
