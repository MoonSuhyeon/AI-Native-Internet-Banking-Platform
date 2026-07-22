package com.bank.ai.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code agent_audit_log} 테이블 접근 — JdbcTemplate 기반 (JPA 제외 모듈).
 *
 * <p>INSERT 전용. UPDATE/DELETE 는 DB 트리거(PG) 에서 차단됨.
 */
@Repository
@RequiredArgsConstructor
public class AuditLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final String INSERT_SQL = """
            INSERT INTO agent_audit_log
              (rev_id, schema_version, track,
               request_snapshot, opinion_json, tool_calls_json,
               raw_llm_response, pii_masked, fallback_reason, created_at)
            VALUES
              (:revId, 'v1', :track,
               CAST(:requestSnapshot AS VARCHAR), CAST(:opinionJson AS VARCHAR), CAST(:toolCallsJson AS VARCHAR),
               :rawLlmResponse, :piiMasked, :fallbackReason, :createdAt)
            """;

    private static final String SELECT_BY_REV_ID = """
            SELECT rev_id, track,
                   CAST(request_snapshot AS VARCHAR) AS request_snapshot,
                   CAST(opinion_json AS VARCHAR) AS opinion_json,
                   CAST(tool_calls_json AS VARCHAR) AS tool_calls_json,
                   raw_llm_response, pii_masked, fallback_reason
            FROM agent_audit_log
            WHERE rev_id = :revId
            ORDER BY created_at DESC
            LIMIT 1
            """;

    /**
     * 감사 로그를 삽입한다.
     *
     * <p>호출자는 {@code @Transactional(propagation = REQUIRES_NEW)} 컨텍스트에서 호출해야 한다.
     */
    public void insert(AgentAuditRecord record) {
        var params = new MapSqlParameterSource()
                .addValue("revId", record.revId())
                .addValue("track", record.track())
                .addValue("requestSnapshot", record.requestSnapshotJson())
                .addValue("opinionJson", record.opinionJson())
                .addValue("toolCallsJson", record.toolCallsJson())
                .addValue("rawLlmResponse", record.rawLlmResponse())
                .addValue("piiMasked", record.piiMasked())
                .addValue("fallbackReason", record.fallbackReason())
                .addValue("createdAt", Timestamp.from(Instant.now()));

        jdbc.update(INSERT_SQL, params);
    }

    /** revId 기준 최신 1건 조회 (Admin 조회, 재현성 검증용). */
    public Optional<AgentAuditRecord> findLatestByRevId(Long revId) {
        var params = new MapSqlParameterSource("revId", revId);
        List<AgentAuditRecord> results = jdbc.query(SELECT_BY_REV_ID, params,
                (rs, rowNum) -> new AgentAuditRecord(
                        rs.getLong("rev_id"),
                        rs.getString("track"),
                        rs.getString("request_snapshot"),
                        rs.getString("opinion_json"),
                        rs.getString("tool_calls_json"),
                        rs.getString("raw_llm_response"),
                        rs.getBoolean("pii_masked"),
                        rs.getString("fallback_reason")
                ));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
