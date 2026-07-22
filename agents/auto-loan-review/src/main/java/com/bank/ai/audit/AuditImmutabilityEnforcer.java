package com.bank.ai.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Collections;

/**
 * 애플리케이션 기동 시 {@code agent_audit_log} 테이블의 INSERT-ONLY 트리거 존재를 검증한다.
 *
 * <p>PostgreSQL 환경: {@code trg_aal_no_update}, {@code trg_aal_no_delete} 두 트리거가
 * 반드시 존재해야 한다. 없으면 {@link IllegalStateException} 을 던져 기동을 중단한다.
 *
 * <p>H2 환경(dev/test): 트리거 미지원 — 경고 로그 후 pass. {@link AuditLogService}가
 * H2 에서 기능적으로 동작하지만 불변성은 애플리케이션 레벨에서만 보장된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditImmutabilityEnforcer implements ApplicationRunner {

    private static final String TRIGGER_CHECK_SQL = """
            SELECT COUNT(*) FROM information_schema.triggers
            WHERE LOWER(trigger_name) IN ('trg_aal_no_update', 'trg_aal_no_delete')
            """;

    private final DataSource dataSource;
    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        if (isH2()) {
            log.warn("[Audit] H2 데이터베이스 감지 — INSERT-ONLY 트리거 검증 건너뜀 (dev/test 모드). " +
                     "운영 환경(PostgreSQL)에서는 V1 마이그레이션 트리거가 필수입니다.");
            return;
        }

        Integer count = jdbc.queryForObject(
                TRIGGER_CHECK_SQL, Collections.emptyMap(), Integer.class);

        if (count == null || count < 2) {
            throw new IllegalStateException(
                    "[Audit] agent_audit_log INSERT-ONLY 트리거 누락! " +
                    "V1 마이그레이션이 정상 적용됐는지 확인하세요. " +
                    "(trg_aal_no_update, trg_aal_no_delete 둘 다 필요)");
        }

        log.info("[Audit] INSERT-ONLY 트리거 검증 완료: trg_aal_no_update, trg_aal_no_delete 존재 확인");
    }

    private boolean isH2() {
        try (var conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            return url != null && url.toLowerCase().contains("h2");
        } catch (SQLException e) {
            log.warn("[Audit] 데이터베이스 URL 확인 실패 — 트리거 검증 건너뜀", e);
            return true;   // 안전하게 skip
        }
    }
}
