package com.bank.deposit.security;

import com.bank.deposit.client.CustomerServiceClient;
import com.bank.deposit.client.dto.TransactionApprovalVerifyRequest;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
        AmountTierPolicy tierPolicy = new AmountTierPolicy();
        ReflectionTestUtils.setField(tierPolicy, "normalFrom", 100_000L);
        ReflectionTestUtils.setField(tierPolicy, "highFrom", 5_000_000L);
        ReflectionTestUtils.setField(tierPolicy, "veryHighFrom", 50_000_000L);

        gate = new TransferApprovalGate(customerServiceClient, tierPolicy);
        ReflectionTestUtils.setField(gate, "required", false);
    }

    @Nested
    @DisplayName("금액 구간")
    class AmountTiering {

        @Test
        @DisplayName("소액은 토큰 없이 통과한다")
        void smallAmountNeedsNoToken() {
            ReflectionTestUtils.setField(gate, "required", true);

            // 소액까지 매번 본인 확인을 요구하면 아무도 쓰지 않고,
            // 결국 통제 자체를 끄게 된다.
            gate.verify(null, FROM, TO, 50_000L);

            verifyNoInteractions(customerServiceClient);
        }

        @Test
        @DisplayName("소액을 넘으면 토큰을 요구한다")
        void aboveSmallRequiresToken() {
            ReflectionTestUtils.setField(gate, "required", true);

            assertThatThrownBy(() -> gate.verify(null, FROM, TO, 100_000L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_APPROVAL_REQUIRED));
        }

        @Test
        @DisplayName("초거액은 토큰이 있어도 비대면으로 처리하지 않는다")
        void veryHighGoesToBranchEvenWithToken() {
            // 사고 시 되돌릴 수 없는 금액을 자동화된 경로에 두지 않는다.
            assertThatThrownBy(() -> gate.verify("token-1", FROM, TO, 50_000_000L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_AMOUNT_REQUIRES_BRANCH));

            verifyNoInteractions(customerServiceClient);
        }

        @Test
        @DisplayName("금액을 모르면 가장 강한 구간으로 본다")
        void unknownAmountIsTreatedAsStrongest() {
            // null 을 소액으로 취급하면 금액을 빼는 것이 통제를 우회하는 길이 된다.
            assertThatThrownBy(() -> gate.verify("token-1", FROM, TO, null))
                    .isInstanceOf(BusinessException.class);
        }
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
