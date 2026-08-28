package com.bank.customer.authorization;

import com.bank.customer.party.domain.Employee;
import com.bank.customer.party.repository.EmployeeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 인가 규칙을 고정한다.
 *
 * <p>이 API 의 요점은 <b>"직원 정보를 준다" 가 아니라 "해도 되는지 답한다"</b> 는 것이다.
 * 그래서 테스트도 필드가 잘 채워지는지가 아니라 <b>어떤 조합이 막히는지</b>를 묻는다.
 *
 * <p>기본은 거절이다. 정책에 적히지 않은 조합이 통과하면, 새 자원을 추가할 때
 * 정책을 안 적어도 열리게 된다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("직원 인가 판단 — 기본은 거절")
class EmployeeAuthorizationServiceTest {

    @Mock
    EmployeeRepository employeeRepository;

    @InjectMocks
    EmployeeAuthorizationService service;

    @Test
    @DisplayName("창구 직원은 사유를 적으면 계좌를 읽을 수 있다")
    void teller_with_reason_may_read_accounts() {
        givenEmployee(9001L, "TELLER", "0001");

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9001L, "DEPOSIT_ACCOUNT", "READ", "1", "민원 상담 확인"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.role()).isEqualTo("TELLER");
        assertThat(d.branchCode()).isEqualTo("0001");
    }

    @Test
    @DisplayName("사유가 없으면 역할이 맞아도 거절된다")
    void reason_is_required_for_customer_financial_data() {
        givenEmployee(9001L, "TELLER", "0001");

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9001L, "DEPOSIT_ACCOUNT", "READ", "1", "  "));

        assertThat(d.allowed()).isFalse();
        assertThat(d.denyCode()).isEqualTo(AuthorizationDecision.DENY_REASON_REQUIRED);
    }

    @Test
    @DisplayName("마케팅은 개인 잔액을 읽지 못한다")
    void marketing_may_not_read_individual_balances() {
        givenEmployee(9010L, "HQ_MARKETING", "0000");

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9010L, "DEPOSIT_ACCOUNT", "READ", "1", "캠페인 대상 확인"));

        assertThat(d.allowed())
                .as("마케팅은 집계로 일하지 개인 잔액을 열람할 일이 없다")
                .isFalse();
        assertThat(d.denyCode()).isEqualTo(AuthorizationDecision.DENY_ROLE_NOT_PERMITTED);
    }

    @Test
    @DisplayName("퇴사·정지 직원은 거절된다")
    void inactive_employee_is_denied() {
        given(employeeRepository.findActiveByEmployeeId(anyLong())).willReturn(Optional.empty());

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9999L, "DEPOSIT_ACCOUNT", "READ", "1", "확인"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.denyCode()).isEqualTo(AuthorizationDecision.DENY_EMPLOYEE_INACTIVE);
        assertThat(d.role())
                .as("찾지 못했으므로 역할 스냅샷도 없다")
                .isNull();
    }

    @Test
    @DisplayName("정책에 없는 자원은 막는다")
    void unknown_resource_is_denied() {
        givenEmployee(9001L, "ADMIN", "0001");

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9001L, "LOAN_APPLICATION", "READ", "1", "확인"));

        assertThat(d.allowed())
                .as("모르는 자원이 통과하면 새 자원을 추가할 때 정책을 안 적어도 열린다")
                .isFalse();
        assertThat(d.denyCode()).isEqualTo(AuthorizationDecision.DENY_UNKNOWN_RESOURCE);
    }

    @Test
    @DisplayName("쓰기 행위는 아직 정책에 없으므로 막힌다")
    void write_action_is_not_permitted_yet() {
        givenEmployee(9001L, "ADMIN", "0001");

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9001L, "DEPOSIT_ACCOUNT", "WRITE", "1", "확인"));

        assertThat(d.allowed())
                .as("Phase 1 은 읽기만 다룬다. 쓰기를 열려면 정책을 먼저 적어야 한다")
                .isFalse();
        assertThat(d.denyCode()).isEqualTo(AuthorizationDecision.DENY_UNKNOWN_RESOURCE);
    }

    @Test
    @DisplayName("모르는 grade 값은 역할 없음으로 본다")
    void unknown_grade_is_treated_as_no_role() {
        givenEmployee(9001L, "SOMETHING_ELSE", "0001");

        AuthorizationDecision d = service.decide(new AuthorizationRequest(
                9001L, "DEPOSIT_ACCOUNT", "READ", "1", "확인"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.denyCode()).isEqualTo(AuthorizationDecision.DENY_ROLE_NOT_PERMITTED);
    }

    private void givenEmployee(Long employeeId, String gradeCode, String branchCode) {
        Employee employee = Employee.builder()
                .partyId(employeeId)
                .branchCode(branchCode)
                .gradeCode(gradeCode)
                .statusCode("ACTIVE")
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(employee, "employeeId", employeeId);
        given(employeeRepository.findActiveByEmployeeId(employeeId)).willReturn(Optional.of(employee));
    }
}
