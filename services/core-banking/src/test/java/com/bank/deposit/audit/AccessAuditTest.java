package com.bank.deposit.audit;

import com.bank.deposit.config.JpaAuditingConfig;
import com.bank.deposit.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 열람 감사의 계약을 고정한다.
 *
 * <p>가장 중요한 것은 <b>거절도 남는가</b> 다. 성공만 기록하면 "봐서는 안 되는 것을
 * 보려 했다" 가 사라진다 — 감사에서 정작 필요한 것은 그쪽이다.
 *
 * <p><b>테스트를 트랜잭션 밖에서 돌리는 이유.</b> 감사 기록은 호출부와 분리된
 * 트랜잭션으로 커밋된다. 테스트가 트랜잭션 안에서 돌면 그 커밋이 테스트 롤백에
 * 묻히지 않고 다음 테스트로 새어 나간다. 여기서는 <b>실제 동작대로</b> 커밋되게
 * 두고, 각 테스트 앞에서 지운다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, AccessAuditRecorder.class, AccessAuditWriter.class, AccessActorResolver.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("열람 감사 — 허용도 거절도 남는다")
class AccessAuditTest {

    @Autowired
    AccessAuditRecorder recorder;

    @Autowired
    AccessActorResolver resolver;

    @Autowired
    AccessAuditRepository repository;

    @BeforeEach
    void clearAuditLog() {
        repository.deleteAll();
    }

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Test
    @DisplayName("직원이 사유 없이 부르면 거절되고, 그 시도가 기록된다")
    void employee_without_reason_is_denied_and_recorded() {
        assertThatThrownBy(() -> resolver.resolve(
                "9001", null, null, null, "trace-1", "ACCOUNT_LIST", "1"))
                .isInstanceOf(BusinessException.class);

        var denied = repository.findByResultCodeOrderByAccessedAtDesc("DENIED");
        assertThat(denied)
                .as("막기만 하고 남기지 않으면 시도가 보이지 않는다")
                .hasSize(1);
        assertThat(denied.get(0).getActorEmployeeId()).isEqualTo(9001L);
        assertThat(denied.get(0).getDeniedReason()).isEqualTo("조회 사유 미기재");
        assertThat(denied.get(0).getAccessActionCode()).isEqualTo("ACCOUNT_LIST");
    }

    @Test
    @DisplayName("행위자 헤더가 아예 없으면 거절되고 기록된다")
    void missing_actor_is_denied_and_recorded() {
        assertThatThrownBy(() -> resolver.resolve(
                null, null, null, null, null, "ACCOUNT_LIST", "1"))
                .isInstanceOf(BusinessException.class);

        assertThat(repository.findByResultCodeOrderByAccessedAtDesc("DENIED"))
                .hasSize(1)
                .allSatisfy(a -> assertThat(a.getDeniedReason()).isEqualTo("행위자 헤더 없음"));
    }

    @Test
    @DisplayName("고객이 남의 것을 보려 하면 거절되고 기록된다")
    void customer_touching_other_customer_is_denied() {
        AccessActor customer = resolver.resolve(
                null, "1", null, null, "trace-2", "ACCOUNT_LIST", "1");

        assertThatThrownBy(() ->
                resolver.requireOwnershipIfCustomer(customer, "ACCOUNT_LIST", "999"))
                .isInstanceOf(BusinessException.class);

        assertThat(repository.findByResultCodeOrderByAccessedAtDesc("DENIED"))
                .hasSize(1)
                .allSatisfy(a -> {
                    assertThat(a.getDeniedReason()).isEqualTo("타인 고객 데이터 접근");
                    assertThat(a.getTargetCustomerId()).isEqualTo("999");
                });
    }

    @Test
    @DisplayName("직원이 사유를 적으면 통과하고, 허용도 기록된다")
    void employee_with_reason_is_allowed_and_recorded() {
        AccessActor actor = resolver.resolve(
                "9001", null, null, "민원 상담 확인", "trace-3", "ACCOUNT_LIST", "1");
        assertThat(actor.isEmployee()).isTrue();

        recorder.allowed(actor, "ACCOUNT_LIST", "1", null);

        var logs = repository.findByTargetCustomerIdOrderByAccessedAtDesc("1");
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResultCode()).isEqualTo("ALLOWED");
        assertThat(logs.get(0).getAccessReason()).isEqualTo("민원 상담 확인");
        assertThat(logs.get(0).getTraceId()).isEqualTo("trace-3");
    }

    @Test
    @DisplayName("본인 조회는 사유 없이도 통과한다")
    void customer_self_access_needs_no_reason() {
        AccessActor actor = resolver.resolve(
                null, "1", null, null, null, "ACCOUNT_LIST", "1");

        resolver.requireOwnershipIfCustomer(actor, "ACCOUNT_LIST", "1");

        assertThat(repository.findByResultCodeOrderByAccessedAtDesc("DENIED")).isEmpty();
    }

    @Test
    @DisplayName("직원 토큰이 있으면 고객 헤더가 같이 와도 직원으로 본다")
    void employee_wins_over_customer_header() {
        assertThatThrownBy(() -> resolver.resolve(
                "9001", "1", null, null, null, "ACCOUNT_LIST", "1"))
                .as("고객으로 낮춰 보면 사유 요구가 빠져나간다")
                .isInstanceOf(BusinessException.class);
    }
}
