package com.bank.deposit.dto.request;

public record TermApplicationManagementRequest(
        Long commonTermId,
        Long termTargetId,
        String businessTypeCode,
        Boolean isRequired,
        String registeredAt,
        String modifiedAt
) {}
