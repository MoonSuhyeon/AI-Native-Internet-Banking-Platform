package com.bank.payment.domain.service;

import com.bank.payment.domain.PaymentInstruction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 타행이체 수수료가 실제로 잔액에서 빠지는지.
 *
 * <p><b>왜 이 테스트가 필요한가.</b> 예전에는 이체금액만 출금하면서 원장에는 송신계좌
 * DEBIT FEE 를 기록했다. 차변=대변은 맞아 원장 검증을 통과했고, 예외도 나지 않았다.
 * 다만 <b>원장 합계와 예금 잔액 합계가 어긋났다</b> — 고객은 수수료를 내지 않았는데
 * 은행은 수수료수익을 인식하는 상태였다.
 *
 * <p>이 결함은 기존 테스트를 하나도 깨뜨리지 않았다. 돈이 맞지 않는 종류의 버그는
 * 대개 이렇게 조용하다.
 *
 * <p>검증과 출금이 <b>같은 값</b>을 쓰는지도 함께 본다. 한쪽만 수수료를 포함하면
 * 잔액이 빠듯한 고객에게서 검증 통과 후 출금 실패가 난다.
 */
class PaymentFeeDebitTest {

    private final PaymentOrchestratorImpl orchestrator =
            Mockito.mock(PaymentOrchestratorImpl.class, Mockito.CALLS_REAL_METHODS);

    private PaymentInstruction instruction(Long feeAmount) {
        return PaymentInstruction.builder()
                .paymentInstructionId("PI-1")
                .feeAmount(feeAmount)
                .build();
    }

    private PaymentCommand command(long transferAmount) {
        return new PaymentCommand("110-222-333333", "004", "999-888-777777", "홍길동",
                transferAmount, null, null, "MOBILE", null, "9001", "T-1", "IDEM-1");
    }

    @Test
    @DisplayName("타행이체는 이체금액과 수수료를 합쳐 출금한다")
    void interBankDebitsAmountPlusFee() {
        Long total = orchestrator.totalDebit(instruction(500L), command(10_000L));

        assertThat(total)
                .as("수수료를 빼먹으면 원장에는 기록되는데 잔액에서는 안 빠진다")
                .isEqualTo(10_500L);
    }

    @Test
    @DisplayName("자행이체는 수수료가 0이라 이체금액만 빠진다")
    void intraBankDebitsAmountOnly() {
        Long total = orchestrator.totalDebit(instruction(0L), command(10_000L));

        assertThat(total).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("수수료가 없으면(null) 이체금액만 빠진다 — 예외로 이체를 막지 않는다")
    void nullFeeIsTreatedAsZero() {
        // 수수료 미설정은 예전 데이터나 신규 경로에서 생길 수 있다.
        // 여기서 NPE 가 나면 이체 자체가 실패한다.
        Long total = orchestrator.totalDebit(instruction(null), command(10_000L));

        assertThat(total).isEqualTo(10_000L);
    }
}
