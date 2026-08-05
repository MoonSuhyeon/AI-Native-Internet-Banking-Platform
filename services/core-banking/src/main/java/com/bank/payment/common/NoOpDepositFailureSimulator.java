package com.bank.payment.common;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Profile("!fault-injection")
@Component
public class NoOpDepositFailureSimulator implements DepositFailureSimulator {

    @Override
    public void checkAndThrow(String receiverAccountNo) {
        // no-op: 운영 환경에서는 장애를 강제하지 않는다.
    }
}
