package com.bank.payment.common;

import com.bank.payment.common.exception.DepositInboundFailureException;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * F8 시나리오: 출금 성공 후 입금 단계에서 수신계 시스템 오류.
 *
 * <p>계좌 자체는 정상(ACTIVE)이어야 한다 — 검증 단계를 통과해 출금까지 간 다음
 * 입금에서 터져야 원자성 롤백을 검증할 수 있다.
 */
@Profile("fault-injection")
@Primary
@Component
public class MockDepositFailureSimulator implements DepositFailureSimulator {

    /** F8 트리거 수신계좌 — 입금 시 시스템 오류(DEP-9001). */
    private static final String F8_TRIGGER_ACCOUNT = "12345678909999";

    @Override
    public void checkAndThrow(String receiverAccountNo) {
        if (F8_TRIGGER_ACCOUNT.equals(receiverAccountNo)) {
            throw new DepositInboundFailureException(
                    "DEP-9001", "F8 시뮬레이션: 입금 처리 중 시스템 오류 (accountNo=" + F8_TRIGGER_ACCOUNT + ")");
        }
    }
}
