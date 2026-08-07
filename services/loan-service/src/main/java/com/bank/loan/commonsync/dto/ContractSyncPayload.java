package com.bank.loan.commonsync.dto;

import java.time.OffsetDateTime;

/**
 * CONTRACT 타입 outbox payload.
 * LoanContract 스냅샷 → CommonContract 생성에 필요한 필드.
 * prodId 는 loan_db FK — 디스패처가 LoanProduct.productId(common PK)를 역참조하는 데 사용.
 */
public record ContractSyncPayload(
        String cntrNo,
        Long customerId,
        String customerNo,
        Long prodId,
        String bizDivCd,
        Long contractAmount,
        String rateTypeCd,
        Integer baseRateBps,
        Integer spreadBps,
        Integer preferentialBps,
        Integer totalRateBps,
        String contractStartDate,
        String contractEndDate,
        Boolean autoTransferYn,
        Integer autoTransferDay,
        OffsetDateTime signedAt,
        String cntrChannelCd,
        Long spotId,
        String spotName,
        Long managerId,
        String managerName,
        Boolean proxyYn,
        String cntrStatusCd
) {}
