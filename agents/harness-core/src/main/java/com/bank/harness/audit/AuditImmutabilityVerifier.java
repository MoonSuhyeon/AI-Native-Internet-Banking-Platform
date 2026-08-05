package com.bank.harness.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.Collections;

/**
 * 감사 테이블의 INSERT-ONLY 보장을 기동 시 확인한다.
 *
 * <p>애플리케이션 코드에 UPDATE/DELETE 경로를 두지 않는 것만으로는 부족하다.
 * DB 콘솔에서 직접 고칠 수 있으면 감사로서 성립하지 않는다. 트리거가 실제로 걸려 있는지를
 * 기동 시점에 검증하고, 없으면 <b>기동을 실패시킨다</b> — 감사가 보장되지 않는 채로
 * 뜨는 것보다 뜨지 않는 편이 낫다.
 *
 * <p>기존 auto-loan-review 구현을 일반화한 것이다. 테이블명이 하드코딩돼 있어
 * 다른 에이전트가 쓸 수 없었다.
 */
@Slf4j
@RequiredArgsConstructor
public class AuditImmutabilityVerifier implements ApplicationRunner {

    private static final String TRIGGER_CHECK_SQL = """
            SELECT COUNT(*) FROM information_schema.triggers
            WHERE LOWER(trigger_name) IN ('trg_harness_audit_no_update', 'trg_harness_audit_no_delete')
            """;

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        if (isH2()) {
            // H2 는 개발·테스트 전용이다. 트리거 문법이 달라 검증하지 않는다.
            log.warn("[audit] H2 감지 — INSERT-ONLY 트리거 검증 생략. 운영(PostgreSQL)에서는 필수다.");
            return;
        }
        Integer count = jdbc.queryForObject(TRIGGER_CHECK_SQL, Collections.emptyMap(), Integer.class);
        if (count == null || count < 2) {
            throw new IllegalStateException(
                    "[audit] harness_audit_log INSERT-ONLY 트리거 누락. "
                    + "마이그레이션이 적용됐는지 확인하세요 "
                    + "(trg_harness_audit_no_update, trg_harness_audit_no_delete 둘 다 필요).");
        }
        log.info("[audit] INSERT-ONLY 트리거 검증 완료");
    }

    private boolean isH2() {
        try (var conn = dataSource.getConnection()) {
            return "H2".equalsIgnoreCase(conn.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            log.warn("[audit] DB 종류 판별 실패 — 트리거 검증을 진행한다", e);
            return false;
        }
    }
}
