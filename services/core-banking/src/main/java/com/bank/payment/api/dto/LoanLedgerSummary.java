package com.bank.payment.api.dto;

/**
 * 하루치 여신 원장 집계 — 대사의 <b>정본</b> 쪽.
 *
 * <p>여신은 자기 보조부에서 같은 값을 독립적으로 산출한다. 두 값을 맞춰 보는 것이
 * 대사이고, 한쪽을 다른 쪽에서 뽑아 오면 대사가 항등식이 되어 아무것도 검증하지
 * 못한다. 그래서 이 API 는 <b>원장에서만</b> 계산한다.
 *
 * @param baseDate           기준 영업일 (yyyyMMdd)
 * @param disbursedAmount    실행액. {@code LOAN_RECEIVABLE} 차변 합계다
 * @param disbursedCount     실행 건수
 * @param repaidAmount       상환액. 여신이 개시한 결제의 출금 분개 합계다
 * @param repaidCount        상환 건수
 */
public record LoanLedgerSummary(
        String baseDate,
        Long disbursedAmount,
        Integer disbursedCount,
        Long repaidAmount,
        Integer repaidCount
) {}
