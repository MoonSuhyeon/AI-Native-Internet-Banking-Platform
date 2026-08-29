package com.bank.loan.payment.client.dto;

/**
 * 원장이 말한 하루치 여신 집계 — 대사의 정본 쪽.
 *
 * <p>여신은 자기 보조부에서 같은 값을 독립적으로 산출한다. 한쪽을 다른 쪽에서 뽑아
 * 오면 대사가 항등식이 되어 아무것도 검증하지 못한다.
 */
public record LoanLedgerSummary(
        String baseDate,
        Long disbursedAmount,
        Integer disbursedCount,
        Long repaidAmount,
        Integer repaidCount
) {}
