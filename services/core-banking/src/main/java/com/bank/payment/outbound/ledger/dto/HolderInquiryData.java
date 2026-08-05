package com.bank.payment.outbound.ledger.dto;

public record HolderInquiryData(
        String accountNo,
        String holderName,
        String holderType,      // INDIVIDUAL / CORPORATE / JOINT
        String customerId,
        Boolean deceasedFlag,
        Integer version
) {}
