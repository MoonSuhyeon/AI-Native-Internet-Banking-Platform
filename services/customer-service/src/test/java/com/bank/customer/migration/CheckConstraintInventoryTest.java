package com.bank.customer.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHECK 제약 인벤토리 통합테스트 (실제 PostgreSQL + 전체 Flyway).
 *
 * <p><b>왜 필요한가.</b> 단위테스트는 Flyway off + H2 엔티티 DDL 로 돌아 마이그레이션 SQL 의
 * DB 차원 안전장치(CHECK 제약)를 만들지 않는다. 그래서 잘못된 데이터를 넣는 코드가 있어도
 * H2 는 그냥 통과시키고(운영 PostgreSQL 이었다면 거부됐을) 테스트가 초록불이 되는 거짓 안심이
 * 생긴다({@link AuthSecurityMigrationTest} 의 PIN 회귀 버그가 그 예).
 *
 * <p>이 테스트는 실제 Postgres 에 <b>전 마이그레이션(V1~최신)</b>을 적용한 뒤, 최종 스키마에
 * 운영이 기대하는 <b>명명된 CHECK 제약 30종이 모두 존재</b>하는지 한 번에 검증한다. CHECK 가
 * 존재한다는 것은 곧 PostgreSQL 이 규칙 위반 데이터를 실제로 거부한다는 보증이므로, 어떤
 * 마이그레이션이 안전장치를 실수로 빠뜨리거나(DROP 후 재생성 누락) 이름이 어긋나면 CI 가 잡는다.
 *
 * <p>대상에서 제외한 4종(chk_otp_status·chk_otp_type·chk_auth_token_status·chk_security_card_status)은
 * V7 이 추가했다가 V9 가 테이블째 DROP 해 최종 스키마에 존재하지 않으므로 기대 집합에 넣지 않는다.
 *
 * <p>주의: 로컬 Docker Desktop 29 ↔ docker-java 비호환으로 로컬에선 실패할 수 있고, CI(Linux)에서 그린이다.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckConstraintInventoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** 최종 스키마에 반드시 존재해야 하는 명명 CHECK 제약 (운영 안전장치 인벤토리). */
    private static final List<String> EXPECTED_CHECK_CONSTRAINTS = List.of(
            "chk_api_token_type",
            "chk_auth_method_primary",
            "chk_auth_method_type",
            "chk_certificate_status",
            "chk_credential_account_status",
            "chk_customer_lifecycle",
            "chk_duplicate_review_case_distinct_parties",
            "chk_employee_status",
            "chk_fds_detection_status",
            "chk_fds_incident_fss_reported",
            "chk_fds_rule_action_type",
            "chk_fds_rule_active",
            "chk_fds_rule_risk_weight",
            "chk_identity_verification_agency",
            "chk_identity_verification_consumed",
            "chk_login_attempt_success",
            "chk_login_session_mfa",
            "chk_login_session_status",
            "chk_mobile_auth_verified",
            "chk_party_org_foreign_corp",
            "chk_party_org_subtype",
            "chk_party_person_pep",
            "chk_party_relation_no_self",
            "chk_party_role_end",
            "chk_qr_login_token_status",
            "chk_registered_device_designated_pc",
            "chk_registered_device_status",
            "chk_registered_device_trusted",
            "chk_registered_device_type",
            "chk_sanction_screening_hit_rate");

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate(); // 하나라도 실패하면 예외로 테스트 실패
    }

    @Test
    @DisplayName("전 마이그레이션 적용 후 운영 안전장치 CHECK 제약 30종이 모두 존재한다")
    void allExpectedCheckConstraintsPresent() throws Exception {
        Set<String> present = presentCheckConstraints();
        assertThat(present)
                .as("실 PostgreSQL 스키마의 CHECK 제약 (누락 시 안전장치 소실 = 잘못된 데이터가 DB를 통과)")
                .containsAll(EXPECTED_CHECK_CONSTRAINTS);
    }

    // ── helpers ──────────────────────────────────────────────────

    /** public 스키마의 모든 CHECK 제약(contype='c') 이름. */
    private Set<String> presentCheckConstraints() throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT conname FROM pg_constraint " +
                     "WHERE contype = 'c' AND connamespace = 'public'::regnamespace")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
