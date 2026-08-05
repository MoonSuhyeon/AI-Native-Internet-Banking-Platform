package com.bank.payment.common.exception;

/**
 * txStep4 분개 INSERT 실패 — 보상 트랜잭션 필요 신호 (F5 시나리오, P-002).
 * B-3 출금·B-4 입금이 이미 성공한 상태에서 분개 INSERT가 실패했으므로
 * REVERSING 진입 후 B-5 출금취소 필수.
 * DepositInboundFailureException(B-4 응답 실패)과 구분: 이 예외는 DB 레이어 실패.
 */
public class LedgerInsertFailureException extends RuntimeException {

    public LedgerInsertFailureException(String message) {
        super(message);
    }
}
