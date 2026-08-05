package com.bank.payment.common;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영 프로파일용 LedgerFailureSimulator — 아무것도 하지 않는다(no-op).
 * <p>
 * mock 프로파일 외 모든 환경(local, dev, staging, prod)에서 활성화된다.
 * 분개 INSERT 장애 시뮬레이션이 필요 없으므로 checkAndThrow는 즉시 반환한다.
 */
@Profile("!mock")
@Component
public class NoOpLedgerFailureSimulator implements LedgerFailureSimulator {

    @Override
    public void checkAndThrow(String receiverAccountNo) {
        // no-op: 운영 환경에서는 장애를 강제하지 않는다.
    }
}
