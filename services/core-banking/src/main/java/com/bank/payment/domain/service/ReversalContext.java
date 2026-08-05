package com.bank.payment.domain.service;

public record ReversalContext(
        String rejectCode,
        String rejectMessage,
        String referenceNo,
        String reversalReason,
        String failureCategory,
        String outboxFailureCategory,
        String triggeredBy,
        String operatorId,
        String networkType
) {}
