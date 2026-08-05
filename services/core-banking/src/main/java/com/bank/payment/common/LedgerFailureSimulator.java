package com.bank.payment.common;

/**
 * 분개(Ledger) INSERT 직전 장애를 시뮬레이션하는 전략 인터페이스.
 * <p>
 * 운영 프로파일(@Profile("!mock"))에서는 no-op 구현이 등록되어 아무 일도 하지 않는다.
 * 테스트 프로파일(@Profile("mock"))에서는 Mock 구현이 등록되어 특정 수신계좌번호에
 * 대해 {@link com.bank.payment.common.exception.LedgerInsertFailureException}을 던진다.
 * <p>
 * PaymentTransactionService.txStep4 내 분개 INSERT 직전에 호출하며,
 * 운영 코드에서 테스트 트리거(하드코딩 계좌번호)를 완전히 분리한다.
 */
public interface LedgerFailureSimulator {

    /**
     * 수신계좌번호에 따라 분개 INSERT 실패를 시뮬레이션한다.
     *
     * @param receiverAccountNo 수신계좌번호
     * @throws com.bank.payment.common.exception.LedgerInsertFailureException
     *         mock 프로파일에서 F5 트리거 계좌번호 일치 시
     */
    void checkAndThrow(String receiverAccountNo);
}
