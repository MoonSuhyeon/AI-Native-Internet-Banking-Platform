package com.bank.payment.outbound.feign.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountInquiryData(
        Long accountId,                               // deposit PK — by-number 응답 직접 필드명
        @JsonProperty("accountNumber") String accountNo,
        String accountType,
        String accountStatus,   // ACTIVE / DORMANT / SUSPENDED / CLOSED (deposit 실제 enum)
        String productCode,
        String openedAt,
        String closedAt,
        String branchCode,
        Boolean fraudFlag,      // deposit 미제공 → null. Boolean.TRUE.equals(null)=false 로 안전.
        Integer version,
        // D-REQ-3/4 해소 전: deposit 별도 balance/limit API 없음 → by-number 응답 필드로 대체.
        // balance: Account 엔티티 NOT NULL (default 0). dailyWithdrawLimit/atmWithdrawLimit: nullable.
        BigDecimal balance,
        BigDecimal dailyWithdrawLimit,
        BigDecimal atmWithdrawLimit
) {}
