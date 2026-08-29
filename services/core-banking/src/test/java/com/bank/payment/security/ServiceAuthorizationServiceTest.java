package com.bank.payment.security;

import com.bank.common.security.Sha256;
import com.bank.common.security.service.AuthorizationDecision;
import com.bank.common.security.service.DenyReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인가 판정 6분기와 거절 감사를 고정한다.
 *
 * <p><b>왜 거절마다 테스트가 있는가.</b> 거절 사유는 감사에서 집계되는 값이다.
 * 두 사유가 뭉개지면 "설정 실수" 와 "침해 시도" 를 나중에 구분할 수 없다.
 *
 * <p>DB 없이 돈다. 매퍼를 손으로 구현한 이유는 판정 순서 자체를 검증하려는
 * 것이지 SQL 을 검증하려는 것이 아니기 때문이다.
 */
class ServiceAuthorizationServiceTest {

    private static final String CREDENTIAL = "dev-secret-loan-service-credential";
    private static final String SERVICE_ID = "LOAN_SERVICE";
    private static final String SENDER = "0040000000002";

    private StubMapper mapper;
    private List<ServiceAuthorizationLogEntry> written;
    private ServiceAuthorizationService service;

    @BeforeEach
    void setUp() {
        mapper = new StubMapper();
        written = new ArrayList<>();
        service = new ServiceAuthorizationService(mapper, new ServiceAuthorizationAuditWriter(mapper) {
            @Override
            public void write(ServiceAuthorizationLogEntry entry) {
                written.add(entry);
            }
        });
    }

    private ServiceAuthorizationService.RequestContext ctx(Long amount, String sender) {
        return new ServiceAuthorizationService.RequestContext(
                amount, sender, "IDEM-1", "trace-1", "/api/v1/internal/payments");
    }

    @Test
    @DisplayName("권한·한도·계좌가 모두 맞으면 허용한다")
    void allows() {
        AuthorizationDecision d = service.authorize(CREDENTIAL, "LOAN_DISBURSE", ctx(1_000_000L, SENDER));

        assertThat(d.allowed()).isTrue();
        assertThat(d.serviceId()).isEqualTo(SERVICE_ID);
        assertThat(written).singleElement()
                .extracting(ServiceAuthorizationLogEntry::decision)
                .isEqualTo(ServiceAuthorizationLogEntry.DECISION_ALLOW);
    }

    @Test
    @DisplayName("자격증명이 없으면 신원을 세우지 못한 거절로 남긴다")
    void deniesMissingCredential() {
        AuthorizationDecision d = service.authorize(null, "LOAN_DISBURSE", ctx(1_000L, SENDER));

        assertThat(d.allowed()).isFalse();
        assertThat(d.denyReason()).isEqualTo(DenyReason.UNKNOWN_CREDENTIAL);
        // 신원을 못 세워도 기록은 남는다. 그 시도가 가장 봐야 할 기록이다.
        assertThat(written).singleElement()
                .satisfies(e -> {
                    assertThat(e.serviceId()).isNull();
                    assertThat(e.decision()).isEqualTo(ServiceAuthorizationLogEntry.DECISION_DENY);
                });
    }

    @Test
    @DisplayName("모르는 자격증명도 신원 미상 거절이다")
    void deniesUnknownCredential() {
        AuthorizationDecision d = service.authorize("아무거나", "LOAN_DISBURSE", ctx(1_000L, SENDER));

        assertThat(d.denyReason()).isEqualTo(DenyReason.UNKNOWN_CREDENTIAL);
    }

    @Test
    @DisplayName("정지된 서비스는 권한을 보기 전에 막는다")
    void deniesSuspendedService() {
        mapper.principalStatus = "SUSPENDED";

        AuthorizationDecision d = service.authorize(CREDENTIAL, "LOAN_DISBURSE", ctx(1_000L, SENDER));

        assertThat(d.denyReason()).isEqualTo(DenyReason.SERVICE_SUSPENDED);
    }

    @Test
    @DisplayName("모르는 작업 이름은 예외가 아니라 거절이다")
    void deniesUnknownOperation() {
        AuthorizationDecision d = service.authorize(CREDENTIAL, "LOAN_EXECUTE", ctx(1_000L, SENDER));

        assertThat(d.denyReason()).isEqualTo(DenyReason.UNKNOWN_OPERATION);
        // 무엇을 부르려 했는지가 남아야 오설정인지 탐색인지 구분할 수 있다.
        assertThat(written).singleElement()
                .extracting(ServiceAuthorizationLogEntry::operation)
                .isEqualTo("LOAN_EXECUTE");
    }

    @Test
    @DisplayName("부여된 적 없는 작업은 NO_PERMISSION 이다")
    void deniesNoPermission() {
        // LOAN_REVERSE 는 업무분리 때문에 시드에서 제외했다. 그 상태가 이렇게 보인다.
        AuthorizationDecision d = service.authorize(CREDENTIAL, "LOAN_REVERSE", ctx(1_000L, SENDER));

        assertThat(d.denyReason()).isEqualTo(DenyReason.NO_PERMISSION);
    }

    @Test
    @DisplayName("회수된 권한은 NO_PERMISSION 과 구분한다")
    void deniesRevokedPermission() {
        mapper.permissionStatus = "REVOKED";

        AuthorizationDecision d = service.authorize(CREDENTIAL, "LOAN_DISBURSE", ctx(1_000L, SENDER));

        assertThat(d.denyReason()).isEqualTo(DenyReason.PERMISSION_REVOKED);
    }

    @Test
    @DisplayName("한도를 넘으면 거절한다")
    void deniesAmountExceeded() {
        AuthorizationDecision d = service.authorize(
                CREDENTIAL, "LOAN_DISBURSE", ctx(100_000_001L, SENDER));

        assertThat(d.denyReason()).isEqualTo(DenyReason.AMOUNT_EXCEEDED);
    }

    @Test
    @DisplayName("허용되지 않은 송신계좌는 거절한다 — 작업을 바꿔 불러도 여기서 걸린다")
    void deniesAccountNotAllowed() {
        AuthorizationDecision d = service.authorize(
                CREDENTIAL, "LOAN_DISBURSE", ctx(1_000L, "001-2000-0000012"));

        assertThat(d.denyReason()).isEqualTo(DenyReason.ACCOUNT_NOT_ALLOWED);
    }

    @Test
    @DisplayName("계좌 목록이 비어 있으면 계좌를 제한하지 않는다")
    void allowsWhenNoAccountPolicy() {
        mapper.allowedAccounts = List.of();

        AuthorizationDecision d = service.authorize(
                CREDENTIAL, "LOAN_DISBURSE", ctx(1_000L, "아무계좌"));

        assertThat(d.allowed()).isTrue();
    }

    /** 판정 순서를 보려는 것이지 SQL 을 보려는 것이 아니라, 손으로 구현한다. */
    private static class StubMapper implements ServiceAuthorizationMapper {

        String principalStatus = ServicePrincipal.STATUS_ACTIVE;
        String permissionStatus = ServicePermission.STATUS_ACTIVE;
        List<String> allowedAccounts = List.of(SENDER);

        @Override
        public ServicePrincipal findPrincipalByCredentialHash(String credentialHash) {
            return Sha256.hex(CREDENTIAL).equals(credentialHash)
                    ? new ServicePrincipal(SERVICE_ID, "여신 서비스", principalStatus)
                    : null;
        }

        @Override
        public ServicePermission findPermission(String serviceId, String operation) {
            // 시드와 같은 상태 — 실행과 상환만 있고 역분개는 없다.
            if (!"LOAN_DISBURSE".equals(operation) && !"LOAN_REPAY".equals(operation)) {
                return null;
            }
            long max = "LOAN_DISBURSE".equals(operation) ? 100_000_000L : 10_000_000L;
            return new ServicePermission(1L, serviceId, operation, max, permissionStatus);
        }

        @Override
        public List<String> findAllowedAccounts(Long permissionId) {
            return allowedAccounts;
        }

        @Override
        public void insertAuthorizationLog(ServiceAuthorizationLogEntry entry) {
            // 감사 기록은 AuditWriter 를 가로채 확인한다.
        }
    }
}
