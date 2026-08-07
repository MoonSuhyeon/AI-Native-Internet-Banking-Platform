package com.bank.docagent.migration;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서류 에이전트 시각 컬럼의 TIMESTAMPTZ 전환 검증 (실제 PostgreSQL).
 *
 * <p><b>왜 필요한가.</b> 단위 테스트는 H2 에 {@code ddl-auto: create-drop} 으로 도는지라
 * 마이그레이션 SQL 이 한 번도 실행되지 않는다. 즉 V5 가 틀려도 아무도 모르고, 운영
 * ({@code ddl-auto: validate})에서 기동 실패로 처음 드러난다.
 *
 * <p>이 전환은 조용히 어긋나기 쉬운 종류다. 이번 작업 중 결제계를 같은 방식으로 바꿨을 때
 * Postgres 드라이버가 TIMESTAMPTZ 를 {@code LocalDateTime} 으로 읽기를 거부해
 * ("Cannot convert the column of type TIMESTAMPTZ to requested type
 * java.time.LocalDateTime") 엔티티 27개를 뒤늦게 고쳐야 했다. 그래서 여기서는 타입만
 * 보지 않고 <b>드라이버가 실제로 읽어내는지</b>까지 확인한다.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimestamptzMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /** V5 가 전환하는 컬럼 + 이미 TIMESTAMPTZ 였던 status_history. */
    private static final String[][] TIME_COLUMNS = {
            {"loan_document_submission", "created_at"},
            {"loan_document_submission", "updated_at"},
            {"loan_forgery_signal", "detected_at"},
            {"identity_verify_cache", "verified_at"},
            {"identity_verify_cache", "expires_at"},
            {"status_history", "changed_at"},
            {"status_history", "created_at"},
    };

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Test
    @DisplayName("시각 컬럼이 전부 timestamptz 다 — 한 스키마 안에서 갈리지 않는다")
    void allTimeColumnsAreTimestamptz() throws SQLException {
        try (Connection c = connect()) {
            for (String[] col : TIME_COLUMNS) {
                assertThat(dataTypeOf(c, col[0], col[1]))
                        .as("%s.%s", col[0], col[1])
                        .isEqualTo("timestamp with time zone");
            }
        }
    }

    /**
     * 우리 테이블 전체를 훑는다. 앞의 테스트가 아는 컬럼만 확인하는 것과 달리, 나중에 누가
     * TIMESTAMP 컬럼을 새로 추가하면 여기서 걸린다.
     *
     * <p>{@code flyway_schema_history} 는 제외한다. Flyway 가 스스로 만드는 테이블이라
     * 우리 규약의 대상이 아니고, 바꾸면 Flyway 가 깨진다. 여신계의 Spring Batch 메타데이터
     * 테이블(BATCH_*)도 같은 이유로 TIMESTAMP 로 남겨 두었다 — 벤더가 소유한 스키마는
     * "통일 안 된 곳"이 아니라 "통일하면 안 되는 곳"이다.
     */
    @Test
    @DisplayName("우리 테이블에 타임존 없는 timestamp 가 남아 있지 않다")
    void noNakedTimestampRemains() throws SQLException {
        List<String> naked = new ArrayList<>();
        try (Connection c = connect();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT table_name, column_name
                     FROM information_schema.columns
                     WHERE table_schema = 'public'
                       AND data_type = 'timestamp without time zone'
                       AND table_name <> 'flyway_schema_history'
                     ORDER BY table_name, column_name
                     """)) {
            while (rs.next()) {
                naked.add(rs.getString(1) + "." + rs.getString(2));
            }
        }
        assertThat(naked).isEmpty();
    }

    @Test
    @DisplayName("드라이버가 OffsetDateTime 으로 읽어낸다 — 엔티티가 쓰는 타입이다")
    void readsBackAsOffsetDateTime() throws SQLException {
        OffsetDateTime written = OffsetDateTime.of(2026, 8, 7, 0, 30, 0, 0, ZoneOffset.ofHours(9));

        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity_verify_cache(cache_key, result, verified_at, expires_at)"
                            + " VALUES(?, ?, ?, ?)")) {
                ps.setString(1, "k-offset");
                ps.setString(2, "PASS");
                ps.setObject(3, written);
                ps.setObject(4, written.plusHours(1));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT verified_at FROM identity_verify_cache WHERE cache_key = ?")) {
                ps.setString(1, "k-offset");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    OffsetDateTime read = rs.getObject(1, OffsetDateTime.class);
                    assertThat(read.toInstant())
                            .as("오프셋 표기는 달라도 같은 순간이어야 한다")
                            .isEqualTo(written.toInstant());
                }
            }
        }
    }

    @Test
    @DisplayName("LocalDateTime 으로는 읽히지 않는다 — 엔티티를 되돌리면 여기서 걸린다")
    void refusesLocalDateTime() throws SQLException {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity_verify_cache(cache_key, result, expires_at)"
                            + " VALUES(?, ?, now() + INTERVAL '1 hour')")) {
                ps.setString(1, "k-local");
                ps.setString(2, "PASS");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT verified_at FROM identity_verify_cache WHERE cache_key = ?")) {
                ps.setString(1, "k-local");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThatThrownBy(() -> rs.getObject(1, LocalDateTime.class))
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("TIMESTAMPTZ");
                }
            }
        }
    }

    private String dataTypeOf(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s 컬럼이 존재해야 한다", table, column).isTrue();
                return rs.getString(1);
            }
        }
    }
}
