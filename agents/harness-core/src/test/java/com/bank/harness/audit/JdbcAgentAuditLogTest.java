package com.bank.harness.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 감사 저장소 통합 테스트.
 *
 * <p>H2 로는 검증할 수 없다. JSONB 캐스팅과 INSERT-ONLY 트리거가 PostgreSQL 고유 기능이고,
 * 이 저장소의 핵심 성질(고칠 수 없음)이 바로 그 트리거에 걸려 있기 때문이다.
 * 문법이 맞는지 모르는 채로 여러 에이전트에 퍼뜨리면 여러 곳에서 동시에 깨진다.
 */
class JdbcAgentAuditLogTest {

    private static PostgreSQLContainer<?> postgres;
    private static NamedParameterJdbcTemplate jdbc;
    private static DataSource dataSource;

    private JdbcAgentAuditLog auditLog;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        var ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
        jdbc = new NamedParameterJdbcTemplate(ds);

        // 배포되는 마이그레이션 스크립트를 그대로 적용한다.
        // 테스트용 DDL 을 따로 쓰면 스크립트가 틀려도 테스트는 통과한다.
        String ddl = new String(JdbcAgentAuditLogTest.class
                .getResourceAsStream("/db/harness/V001__harness_audit_log.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try (var conn = ds.getConnection(); var st = conn.createStatement()) {
            st.execute(ddl);
        }
    }

    @BeforeEach
    void setUp() {
        auditLog = new JdbcAgentAuditLog(jdbc);
        jdbc.getJdbcTemplate().execute("TRUNCATE TABLE harness_audit_log");
    }

    @Test
    @DisplayName("판단을 기록하고 대상별로 최신 건을 되찾는다")
    void 기록_조회() {
        auditLog.record(entry("LOAN_REVIEW", "9001", "trace-a", "{\"amount\":1000}"));

        assertThat(auditLog.findLatest("LOAN_REVIEW", "9001"))
                .hasValueSatisfying(found -> {
                    assertThat(found.agentName()).isEqualTo("auto-loan-review");
                    assertThat(found.traceId()).isEqualTo("trace-a");
                    assertThat(found.requestJson()).contains("amount");
                    assertThat(found.piiMasked()).isTrue();
                });
    }

    @Test
    @DisplayName("도메인이 달라도 같은 저장소를 쓴다 — 이것이 일반화의 목적")
    void 여러_도메인_공존() {
        auditLog.record(entry("LOAN_REVIEW", "9001", "t1", "{}"));
        auditLog.record(entry("FRAUD_CASE", "F-77", "t2", "{}"));
        auditLog.record(entry("CONSULT_SESSION", "S-3", "t3", "{}"));

        assertThat(auditLog.findLatest("FRAUD_CASE", "F-77")).isPresent();
        assertThat(auditLog.findLatest("CONSULT_SESSION", "S-3")).isPresent();
        assertThat(auditLog.findLatest("LOAN_REVIEW", "F-77"))
                .as("subject_type 이 다르면 다른 대상이다")
                .isEmpty();
    }

    @Test
    @DisplayName("같은 대상에 여러 건이면 가장 최근 것을 준다")
    void 최신건_반환() {
        Instant older = Instant.now().minus(1, ChronoUnit.HOURS);
        auditLog.record(new AgentAuditEntry("auto-loan-review", "LOAN_REVIEW", "9001",
                "old-trace", "{}", "{\"decision\":\"HOLD\"}", "[]", null, true, null, older));
        auditLog.record(entry("LOAN_REVIEW", "9001", "new-trace", "{}"));

        assertThat(auditLog.findLatest("LOAN_REVIEW", "9001"))
                .hasValueSatisfying(f -> assertThat(f.traceId()).isEqualTo("new-trace"));
    }

    @Test
    @DisplayName("기록은 수정할 수 없다 — 고칠 수 있으면 감사가 아니다")
    void 수정_차단() {
        auditLog.record(entry("LOAN_REVIEW", "9001", "trace-a", "{}"));

        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update(
                "UPDATE harness_audit_log SET output_json = '{\"decision\":\"조작\"}'"))
                .hasMessageContaining("추가만 가능");
    }

    @Test
    @DisplayName("기록은 삭제할 수도 없다")
    void 삭제_차단() {
        auditLog.record(entry("LOAN_REVIEW", "9001", "trace-a", "{}"));

        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("DELETE FROM harness_audit_log"))
                .hasMessageContaining("추가만 가능");
    }

    @Test
    @DisplayName("불변성 검증기가 트리거 존재를 확인한다")
    void 불변성_검증기_통과() {
        var verifier = new AuditImmutabilityVerifier(dataSource, jdbc);
        verifier.run(null);   // 예외 없이 통과하면 트리거가 걸려 있다는 뜻
    }

    @Test
    @DisplayName("트리거가 없으면 기동을 실패시킨다 — 감사 없이 뜨는 것보다 낫다")
    void 트리거_없으면_기동_실패() {
        jdbc.getJdbcTemplate().execute("DROP TRIGGER trg_harness_audit_no_update ON harness_audit_log");
        jdbc.getJdbcTemplate().execute("DROP TRIGGER trg_harness_audit_no_delete ON harness_audit_log");

        var verifier = new AuditImmutabilityVerifier(dataSource, jdbc);
        assertThatThrownBy(() -> verifier.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INSERT-ONLY 트리거 누락");

        // 뒤 테스트를 위해 되돌린다
        restoreTriggers();
    }

    private void restoreTriggers() {
        jdbc.getJdbcTemplate().execute("""
                CREATE TRIGGER trg_harness_audit_no_update BEFORE UPDATE ON harness_audit_log
                FOR EACH ROW EXECUTE FUNCTION harness_audit_reject_change()
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TRIGGER trg_harness_audit_no_delete BEFORE DELETE ON harness_audit_log
                FOR EACH ROW EXECUTE FUNCTION harness_audit_reject_change()
                """);
    }

    @Test
    @DisplayName("빈 JSON 필드는 기본값으로 채워진다 — JSONB 캐스팅이 null 로 깨지지 않게")
    void 기본값_보정() {
        auditLog.record(new AgentAuditEntry("consultation", "CONSULT_SESSION", "S-9",
                "t", null, null, null, "원문", true, null, null));

        assertThat(auditLog.findLatest("CONSULT_SESSION", "S-9"))
                .hasValueSatisfying(f -> {
                    assertThat(f.requestJson()).isEqualTo("{}");
                    assertThat(f.toolCallsJson()).isEqualTo("[]");
                    assertThat(f.rawLlmResponse()).isEqualTo("원문");
                });
    }

    private static AgentAuditEntry entry(String type, String id, String traceId, String requestJson) {
        return new AgentAuditEntry("auto-loan-review", type, id, traceId,
                requestJson, "{\"decision\":\"APPROVE\"}", "[]", "원문 응답",
                true, null, Instant.now());
    }

    private static java.util.Map<String, Object> noParams() {
        return Collections.emptyMap();
    }
}
