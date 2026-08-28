package com.bank.deposit.client.dto;

/** customer-service 인가 판단 질의. */
public record EmployeeAuthorizationRequest(
        Long employeeId,
        String resource,
        String action,
        String targetCustomerId,
        String reason
) {}
