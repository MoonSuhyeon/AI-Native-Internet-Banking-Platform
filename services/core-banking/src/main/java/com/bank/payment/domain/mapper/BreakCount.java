package com.bank.payment.domain.mapper;

/**
 * 유형별 미해결 불일치 건수 — 지표 게이지의 원천.
 *
 * @param breakType 불일치 유형
 * @param count     미해결 건수
 */
public record BreakCount(String breakType, long count) {
}
