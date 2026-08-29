package com.bank.payment.domain.service;

import java.time.OffsetDateTime;

/**
 * 결제 처리 결과 (도메인 출력). Controller가 PaymentResponse로 매핑.
 * domain이 api/dto를 모르게 하는 출력 경계 객체.
 * failureCategory: FAILED 시 원인 코드, 정상 완결 시 null.
 *
 * @param journalNo 원장 분개 묶음 번호. 자행 완결에서만 채워진다.
 *                  <p>여신이 자기 회계번호를 UUID 로 만들어 붙이던 것을 없애기 위해 싣는다.
 *                  {@code payment_instruction_id} 만으로도 원장을 되짚을 수 있지만,
 *                  Loan 보조부와 원장을 대사할 때 이 값이 조인 키가 된다.
 *                  <p>실패·접수(SCHEDULED)·청산대기(CLEARING)에는 없다. 분개가 아직
 *                  없거나(접수) 이 응답 시점의 분개가 최종이 아니기(청산) 때문이다.
 */
public record PaymentResult(
        String paymentInstructionId,
        String transactionNo,
        String status,
        String failureCategory,
        OffsetDateTime completedAt,
        String journalNo
) {

    /**
     * 분개 번호가 없는 결과.
     *
     * <p>기존 호출부 대부분이 이 형태다(실패·접수·청산대기). 정식 생성자에 null 을
     * 일일이 적게 하면 서른 곳을 고치면서 실수할 자리만 늘어난다.
     */
    public PaymentResult(String paymentInstructionId, String transactionNo, String status,
                         String failureCategory, OffsetDateTime completedAt) {
        this(paymentInstructionId, transactionNo, status, failureCategory, completedAt, null);
    }
}
