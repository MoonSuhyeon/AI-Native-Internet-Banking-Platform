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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 인증보안계 마이그레이션 통합테스트 (실제 PostgreSQL).
 *
 * 단위테스트는 Flyway를 끄고(H2 엔티티 DDL) 돌기 때문에 마이그레이션 SQL의
 * CHECK·FK·DROP이 검증되지 않는다. 이 테스트는 실제 Postgres에 V1~V9를 그대로
 * 적용해 다음을 직접 확인한다.
 *  1) 전 마이그레이션이 fresh DB에 깨짐 없이 적용된다 (migrate() 실패 시 테스트 실패)
 *     — V4의 customer_id=1 테스트 시드 FK 문제는 beforeEachMigrate.sql 콜백이 멱등 처리
 *  2) V9가 auth_method 타입 CHECK를 설계문서 집합으로 복구했다
 *     — 'PIN' 타입 insert 성공 / 제거된 'OTP' 타입 insert 거부 (PIN 등록 버그 회귀 방지)
 *  3) V9가 미사용 테이블 3개를 DROP했고, auth_token 은 V35 가 되살렸다
 *     — step-up 인증이 쓰기 시작했는데 마이그레이션이 함께 오지 않아 이체가 막혔었다
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSecurityMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate(); // V1~V9 + beforeEachMigrate 콜백. 하나라도 실패하면 예외로 테스트 실패
    }

    @Test
    @DisplayName("V9 후 auth_method 타입 CHECK는 PIN·생체를 허용하고 OTP·보안카드를 불허한다")
    void authMethodTypeCheckRestored() throws Exception {
        String def = constraintDef("chk_auth_method_type");
        assertThat(def)
                .contains("'PIN'").contains("'BIO_FACE'").contains("'BIO_FINGER'").contains("'CERT_AXFUL'");
        assertThat(def)
                .doesNotContain("'OTP'").doesNotContain("'SECURITY_CARD'");
    }

    @Test
    @DisplayName("PIN 타입 auth_method insert가 통과한다 (PIN 등록 버그 회귀 방지)")
    void pinTypeInsertSucceeds() {
        assertThatCode(() -> insertAuthMethod("PIN")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V9로 제거된 OTP 타입 insert는 CHECK 제약으로 거부된다")
    void removedTypeInsertRejected() {
        assertThatThrownBy(() -> insertAuthMethod("OTP"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_auth_method_type");
    }

    @Test
    @DisplayName("V9가 제거한 미사용 테이블(otp_device·security_card·security_card_code)은 그대로 없다")
    void unusedTablesStayDropped() throws Exception {
        assertThat(tableExists("otp_device")).isFalse();
        assertThat(tableExists("security_card")).isFalse();
        assertThat(tableExists("security_card_code")).isFalse();
    }

    @Test
    @DisplayName("auth_token 은 V35 가 되살렸다 — 자금이동 step-up 인증이 쓴다")
    void authTokenRestoredForStepUpAuth() throws Exception {
        // V9 가 지운 판단은 그때는 옳았다(설계문서에 없고 참조 코드 0건).
        // 이후 step-up 인증이 이 테이블을 쓰기 시작했는데 되살리는 마이그레이션이
        // 오지 않아, 승인 토큰 발급이 항상 500 이었고 소액을 넘는 이체가 막혔다.
        //
        // 이 단언을 되돌리려면 AuthToken 엔티티와 TransactionApprovalService 도
        // 함께 걷어내야 한다 — 테이블만 다시 지우면 이체가 또 멈춘다.
        assertThat(tableExists("auth_token")).isTrue();
    }

    @Test
    @DisplayName("정상 테이블(auth_method·api_token·pin·mobile_auth)은 유지된다")
    void documentedTablesKept() throws Exception {
        assertThat(tableExists("auth_method")).isTrue();
        assertThat(tableExists("api_token")).isTrue();
        assertThat(tableExists("pin")).isTrue();
        assertThat(tableExists("mobile_auth")).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** customer_id=1은 beforeEachMigrate 콜백이 시드하므로 FK가 충족된다. */
    private void insertAuthMethod(String typeCode) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            st.executeUpdate(
                "INSERT INTO auth_method (customer_id, auth_method_type_code, auth_method_status_code, " +
                "primary_auth_method_yn, auth_method_registered_date, created_at, updated_at, version) " +
                "VALUES (1, '" + typeCode + "', 'ACTIVE', 'F', '20260604', NOW(), NOW(), 0)");
        }
    }

    private String constraintDef(String constraintName) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + constraintName + "'")) {
            assertThat(rs.next()).as("제약 %s 존재", constraintName).isTrue();
            return rs.getString(1);
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection c = conn(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('public." + table + "')")) {
            rs.next();
            return rs.getString(1) != null;
        }
    }
}
