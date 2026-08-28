package com.bank.deposit.audit;

import com.bank.deposit.client.CustomerServiceClient;
import com.bank.deposit.client.dto.EmployeeAuthorizationRequest;
import com.bank.deposit.client.dto.EmployeeAuthorizationResponse;
import com.bank.deposit.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 경계가 <b>둘</b>인지 확인한다.
 *
 * <p>가장 중요한 테스트는 {@code upstream_allow_does_not_open_resource} 다.
 * 상류가 ALLOW 여도 자원 쪽에서 막히는가 — 이것이 아니면 customer-service 가
 * 곧 최종 경계가 되고, 그쪽이 뚫리면 자원이 따라 열린다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("자원 접근 — 경계는 둘이다")
class ResourceAccessGuardTest {

    @Mock
    CustomerServiceClient customerServiceClient;

    @Mock
    AccessAuditRecorder auditRecorder;

    @InjectMocks
    ResourceAccessGuard guard;

    private static final String RESOURCE = "DEPOSIT_ACCOUNT";
    private static final String ACTION = "READ";

    @Test
    @DisplayName("상류가 허용해도 자원 규칙에 걸리면 막힌다")
    void upstream_allow_does_not_open_resource() {
        givenUpstream("ALLOW", null);

        // 직원이고 상류는 ALLOW 다. 그런데 대상이 비어 있어 자원 쪽에서 막는다.
        assertThatThrownBy(() -> guard.authorizeRead(employee(), RESOURCE, ACTION, "  "))
                .as("자원 쪽 판단이 없으면 중앙 인가가 곧 최종 경계가 된다")
                .isInstanceOf(BusinessException.class);

        // 상류 판단(ALLOW)과 최종 결과(DENIED)가 함께 남아야
        // "중앙은 열었는데 자원이 막았다" 가 로그에서 보인다.
        verify(auditRecorder).record(any(), eqStr(RESOURCE), eqStr(ACTION), anyString(),
                any(), eqStr("ALLOW"), any(), eqStr("DENIED"), anyString());
    }

    @Test
    @DisplayName("고객은 상류 판단과 무관하게 자원 쪽에서 타인 접근이 막힌다")
    void customer_cross_access_denied_at_resource_layer() {
        AccessActor customerActor = new AccessActor(
                AccessActor.CUSTOMER, null, "1", null, "확인", "t1");

        assertThatThrownBy(() -> guard.authorizeRead(customerActor, RESOURCE, ACTION, "999"))
                .isInstanceOf(BusinessException.class);

        verify(customerServiceClient, never()).authorizeEmployee(any(), anyString());
        verify(auditRecorder).record(any(), eqStr(RESOURCE), eqStr(ACTION), eqStr("999"),
                any(), any(), any(), eqStr("DENIED"), anyString());
    }

    @Test
    @DisplayName("인가 서비스가 죽으면 통과시키지 않는다 (fail-closed)")
    void authorization_outage_denies() {
        given(customerServiceClient.authorizeEmployee(any(), anyString()))
                .willThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> guard.authorizeRead(employee(), RESOURCE, ACTION, "1"))
                .as("판단을 못 받았는데 통과시키면 인가 장애가 전면 개방이 된다")
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<String> denied = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(any(), anyString(), anyString(), anyString(),
                any(), any(), any(), eqStr("DENIED"), denied.capture());
        assertThat(denied.getValue()).contains("fail-closed");
    }

    @Test
    @DisplayName("상류가 거절하면 자원을 조회하지 않고 거절 사유가 남는다")
    void upstream_deny_is_recorded_with_code() {
        givenUpstream("DENY", "ROLE_NOT_PERMITTED");

        assertThatThrownBy(() -> guard.authorizeRead(employee(), RESOURCE, ACTION, "1"))
                .isInstanceOf(BusinessException.class);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder).record(any(), anyString(), anyString(), anyString(),
                any(), eqStr("DENY"), eqStr("ROLE_NOT_PERMITTED"), eqStr("DENIED"), reason.capture());
        assertThat(reason.getValue()).contains("ROLE_NOT_PERMITTED");
    }

    @Test
    @DisplayName("직원이 통과하면 두 판단이 모두 기록된다")
    void allowed_records_both_decisions() {
        givenUpstream("ALLOW", null);

        guard.authorizeRead(employee(), RESOURCE, ACTION, "1");

        verify(auditRecorder).record(any(), eqStr(RESOURCE), eqStr(ACTION), eqStr("1"),
                any(), eqStr("ALLOW"), any(), eqStr("ALLOWED"), any());
    }

    @Test
    @DisplayName("고객 본인 조회는 인가 서비스를 부르지 않는다")
    void customer_self_access_skips_authorization_call() {
        AccessActor self = new AccessActor(AccessActor.CUSTOMER, null, "1", null, null, "t2");

        guard.authorizeRead(self, RESOURCE, ACTION, "1");

        verify(customerServiceClient, never()).authorizeEmployee(any(), anyString());
        verify(auditRecorder).record(any(), anyString(), anyString(), eqStr("1"),
                any(), any(), any(), eqStr("ALLOWED"), any());
    }

    @Test
    @DisplayName("서비스 대리 조회는 사유가 없으면 막힌다")
    void service_actor_needs_reason() {
        AccessActor svc = new AccessActor(
                AccessActor.SERVICE, null, null, "consultation", null, "t3");

        assertThatThrownBy(() -> guard.authorizeRead(svc, RESOURCE, ACTION, "1"))
                .isInstanceOf(BusinessException.class);
    }

    private void givenUpstream(String decision, String denyCode) {
        given(customerServiceClient.authorizeEmployee(any(EmployeeAuthorizationRequest.class), anyString()))
                .willReturn(new EmployeeAuthorizationResponse(
                        decision, denyCode, 9001L, "TELLER", "0001", null));
    }

    private AccessActor employee() {
        return new AccessActor(AccessActor.EMPLOYEE, 9001L, null, null, "민원 확인", "t0");
    }

    private static String eqStr(String v) {
        return org.mockito.ArgumentMatchers.eq(v);
    }
}
