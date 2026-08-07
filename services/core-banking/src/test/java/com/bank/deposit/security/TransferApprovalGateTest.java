package com.bank.deposit.security;

import com.bank.deposit.client.CustomerServiceClient;
import com.bank.deposit.client.dto.TransactionApprovalVerifyRequest;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 이체 step-up 게이트 — 과도기 동작과 fail-closed 를 함께 지킨다.
 *
 * <p>도입 단계상 토큰은 아직 선택이다. "없으면 통과"가 의도된 동작인 동안에도
 * "있으면 반드시 검증한다"와 "검증 실패면 이체하지 않는다"는 지켜져야 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransferApprovalGate")
class TransferApprovalGateTest {

    @Mock CustomerServiceClient customerServiceClient;

    private TransferApprovalGate gate;

    private static final String FROM = "001-001-000001";
    private static final String TO = "111-222-333";
    private static final Long AMOUNT = 100000L;

    @BeforeEach
    void setUp() {
        gate = new TransferApprovalGate(customerServiceClient);
        ReflectionTestUtils.setField(gate, "required", false);
    }

    @Test
    @DisplayName("토큰이 있으면 인증보안계에 검증을 요청한다")
    void verifiesWhenTokenPresent() {
        gate.verify("token-1", FROM, TO, AMOUNT);

        verify(customerServiceClient).verifyTransactionApproval(
                new TransactionApprovalVerifyRequest("token-1", FROM, TO, AMOUNT));
    }

    @Test
    @DisplayName("검증이 거부되면 이체를 막는다 — 확인되지 않은 승인으로 돈을 보내지 않는다")
    void blocksWhenVerificationFails() {
        willThrow(new RuntimeException("401")).given(customerServiceClient)
                .verifyTransactionApproval(any());

        assertThatThrownBy(() -> gate.verify("token-1", FROM, TO, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TRANSFER_APPROVAL_INVALID));
    }

    @Test
    @DisplayName("인증보안계와 통신이 안 돼도 이체를 막는다 — fail-closed")
    void blocksWhenCustomerServiceUnreachable() {
        willThrow(new IllegalStateException("connection refused")).given(customerServiceClient)
                .verifyTransactionApproval(any());

        assertThatThrownBy(() -> gate.verify("token-1", FROM, TO, AMOUNT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("과도기(required=false) — 토큰이 없으면 통과시킨다")
    void allowsMissingTokenWhileOptional() {
        assertThatCode(() -> gate.verify(null, FROM, TO, AMOUNT)).doesNotThrowAnyException();
        assertThatCode(() -> gate.verify("  ", FROM, TO, AMOUNT)).doesNotThrowAnyException();

        verify(customerServiceClient, never()).verifyTransactionApproval(any());
    }

    @Test
    @DisplayName("required=true — 토큰이 없으면 거부한다")
    void rejectsMissingTokenWhenRequired() {
        ReflectionTestUtils.setField(gate, "required", true);

        assertThatThrownBy(() -> gate.verify(null, FROM, TO, AMOUNT))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TRANSFER_APPROVAL_REQUIRED));
    }
}
