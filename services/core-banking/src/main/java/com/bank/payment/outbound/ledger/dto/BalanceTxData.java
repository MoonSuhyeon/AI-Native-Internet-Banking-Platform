package com.bank.payment.outbound.feign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record BalanceTxData(
        // deposit Transaction.transactionId (PK, Long). B-5 PATCH /transactions/{transactionId}/cancel 용.
        // deposit-service Transaction.java:24-26
        Long transactionId,
        @JsonProperty("transactionNumber") String depositTransactionNo,
        String accountNo,       // deposit 미제공(accountId Long만 반환). D-REQ-1 해결 후 처리.
        BigDecimal amount,
        BigDecimal balanceBefore,
        BigDecimal balanceAfter,
        String transactionAt,
        String transactionType
) {}
