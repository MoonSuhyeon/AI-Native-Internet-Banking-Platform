package com.bank.deposit.dto.request;

public record TermApplicationManagementRequest(
        Long commonTermId,
        Long termTargetId,
        String businessTypeCode,
        String isRequired,
        String registeredAt,
        String modifiedAt
) {}
