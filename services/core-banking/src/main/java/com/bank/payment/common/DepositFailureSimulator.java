package com.bank.payment.common;

/**
 * 입금 단계 장애 주입 이음새.
 *
 * <p>"출금은 성공했는데 입금에서 실패" 는 자행이체에서 가장 중요한 실패 경로다.
 * 예전에는 수신계 전체를 Mock 으로 바꿔 이 상황을 만들었지만, Mock 을 걷어낸 뒤에는
 * 실제 원장 위에서 이 순간만 터뜨릴 방법이 필요하다.
 *
 * <p>계좌를 CLOSED 로 만드는 방법은 쓸 수 없다 — 그러면 출금 이전 검증 단계에서 걸려
 * 정작 검증하려던 경로를 지나가지 않는다.
 *
 * <p>운영 프로파일({@code @Profile("!fault-injection")})에서는 no-op 이다.
 * 분개 실패를 다루는 {@link LedgerFailureSimulator} 와 같은 구조다.
 */
public interface DepositFailureSimulator {

    /** 수신 계좌가 장애 트리거면 예외를 던진다. */
    void checkAndThrow(String receiverAccountNo);
}
