package com.bank.payment.security;

import com.bank.deposit.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 직원 운영 작업 인가를 고정한다.
 *
 * <p>서비스 인가와 나란한 주체 모델이다. 서비스는 자격증명으로 신원을 세우지만
 * 직원은 게이트웨이가 확인해 실어 보낸 신원과 역할로 인가한다 —
 * {@code docs/decisions/transaction-initiator-auth-model.md} §7.
 */
class EmployeeOperationGuardTest {

    private static final String PATH = "/api/v1/internal/reconciliation/run";
    private static final String PARAMS = "businessDate=20260829,network=ALL";

    private List<EmployeeOperationLogEntry> audited;
    private EmployeeOperationGuard guard;

    @BeforeEach
    void setUp() {
        audited = new ArrayList<>();
        EmployeeOperationAuditWriter writer =
                new EmployeeOperationAuditWriter(mock(EmployeeOperationMapper.class)) {
                    @Override
                    public void write(EmployeeOperationLogEntry entry) {
                        audited.add(entry);
                    }
                };
        guard = new EmployeeOperationGuard(writer);
    }

    private void require(String employeeId, String roles) {
        guard.require(employeeId, roles, EmployeeOperation.RECONCILIATION_RUN, PARAMS, PATH);
    }

    @Test
    @DisplayName("운영 역할이 있으면 통과하고 허용을 남긴다")
    void allowsWithRequiredRole() {
        assertThatCode(() -> require("9001", "ROLE_OPS")).doesNotThrowAnyException();

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.decision()).isEqualTo(EmployeeOperationLogEntry.DECISION_ALLOW);
            assertThat(e.employeeId()).isEqualTo("9001");
            // 무엇을 실행했는지가 남아야 한다. "누가 대사를 돌렸다" 만으로는 어느
            // 영업일을 다시 돌렸는지 알 수 없다.
            assertThat(e.parameters()).isEqualTo(PARAMS);
        });
    }

    @Test
    @DisplayName("역할이 여럿이어도 그중 하나면 통과한다")
    void allowsWhenOneOfManyRoles() {
        assertThatCode(() -> require("9001", "ROLE_TELLER,ROLE_OPS,ROLE_COMPLIANCE"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enum 이름으로 와도 받는다")
    void acceptsEnumName() {
        assertThatCode(() -> require("9001", "OPS")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("신원이 없으면 막고, 신원 없는 시도를 남긴다")
    void deniesWithoutIdentity() {
        assertThatThrownBy(() -> require(null, "ROLE_OPS"))
                .isInstanceOf(BusinessException.class);

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.decision()).isEqualTo(EmployeeOperationLogEntry.DECISION_DENY);
            assertThat(e.denyReason()).isEqualTo("NO_EMPLOYEE_IDENTITY");
            // 신원을 못 세운 시도가 오히려 더 봐야 할 기록이다.
            assertThat(e.employeeId()).isNull();
        });
    }

    @Test
    @DisplayName("역할이 모자라면 막고, 신원과 역할을 함께 남긴다")
    void deniesWithoutRequiredRole() {
        assertThatThrownBy(() -> require("9001", "ROLE_TELLER"))
                .isInstanceOf(BusinessException.class);

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.denyReason()).isEqualTo("INSUFFICIENT_ROLE");
            assertThat(e.employeeId()).isEqualTo("9001");
            // 판정 근거를 박제한다. 나중에 역할 구성이 바뀌어도 "그때 무엇을
            // 갖고 있었는가" 가 사실이다.
            assertThat(e.actorRoles()).isEqualTo("ROLE_TELLER");
        });
    }

    @Test
    @DisplayName("역할이 아예 없으면 막는다")
    void deniesWithNoRoles() {
        assertThatThrownBy(() -> require("9001", null))
                .isInstanceOf(BusinessException.class);
        assertThat(audited).singleElement()
                .extracting(EmployeeOperationLogEntry::denyReason)
                .isEqualTo("INSUFFICIENT_ROLE");
    }

    @Test
    @DisplayName("비슷한 이름의 역할을 통과시키지 않는다")
    void doesNotMatchRolePrefix() {
        // 부분 문자열로 비교하면 ROLE_OPS 를 찾을 때 ROLE_OPSX 가 걸린다.
        assertThatThrownBy(() -> require("9001", "ROLE_OPSX"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("거절 사유를 호출자에게 알리지 않는다")
    void doesNotLeakDenyReasonToCaller() {
        // 신원이 없어서인지 역할이 모자라서인지 알려 주면 권한 경계를 탐색하는 데
        // 쓸 수 있다. 두 경우 모두 같은 응답이어야 한다.
        BusinessException noIdentity = catchBusinessException(() -> require(null, "ROLE_OPS"));
        BusinessException noRole = catchBusinessException(() -> require("9001", "ROLE_TELLER"));

        assertThat(noIdentity.getMessage()).isEqualTo(noRole.getMessage());
    }

    private static BusinessException catchBusinessException(Runnable r) {
        try {
            r.run();
            throw new AssertionError("예외가 나지 않았다");
        } catch (BusinessException e) {
            return e;
        }
    }
}
