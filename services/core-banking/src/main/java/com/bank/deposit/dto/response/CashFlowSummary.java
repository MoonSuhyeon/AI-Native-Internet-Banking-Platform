package com.bank.deposit.dto.response;

/**
 * 현금흐름 요약. 네 값 모두 원 단위 정수다(데이터 사전: 금액 = BIGINT 원 단위).
 *
 * <p>{@code estimatedSavingsAmount} 는 순현금흐름을 개월 수로 나눈 값이라 계산 중에는
 * BigDecimal 이지만, scale 0 (RoundingMode.DOWN) 으로 절사해 담으므로 정수다.
 */
public record CashFlowSummary(
        Long totalInflow,
        Long totalOutflow,
        Long netCashFlow,
        Long estimatedSavingsAmount
) {}
