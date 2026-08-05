package com.bank.payment.common;

import com.bank.payment.common.exception.LedgerInsertFailureException;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * mock 프로파일용 LedgerFailureSimulator — F5 시나리오 분개 INSERT 실패 시뮬레이션.
 * <p>
 * 수신계좌번호가 {@value #F5_TRIGGER_ACCOUNT}이면 {@link LedgerInsertFailureException}을 던져
 * txStep4 @Transactional 전체를 롤백시킨다(AUTHORIZED 복귀 → 보상 흐름 진입).
 * <p>
 * 이 클래스는 mock 프로파일에서만 활성화되며, 운영 코드({@link NoOpLedgerFailureSimulator})와
 * 완전히 분리된다.
 */
@Profile("mock")
@Primary
@Component
public class MockLedgerFailureSimulator implements LedgerFailureSimulator {

    /** F5 시나리오: 분개 INSERT 강제 실패 트리거 계좌번호 (변학도) */
    private static final String F5_TRIGGER_ACCOUNT = "88880000";

    @Override
    public void checkAndThrow(String receiverAccountNo) {
        if (F5_TRIGGER_ACCOUNT.equals(receiverAccountNo)) {
            throw new LedgerInsertFailureException(
                    "F5 시뮬레이션: 분개 INSERT 강제 실패 (receiverAccountNo=" + F5_TRIGGER_ACCOUNT + ")");
        }
    }
}
