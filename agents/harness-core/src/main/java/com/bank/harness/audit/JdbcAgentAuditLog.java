package com.bank.harness.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 기반 감사 기록 저장소.
 *
 * <p>JPA 가 아니라 JDBC 인 이유: 감사 테이블은 INSERT 전용이라 영속성 컨텍스트가 줄 이점이 없고,
 * 오히려 더티 체킹으로 UPDATE 가 나갈 여지를 만든다. 기록을 고칠 수 없어야 하는 자리에서는
 * 고칠 수 있는 도구를 쓰지 않는 편이 낫다.
 */
@Slf4j
@RequiredArgsConstructor
public class JdbcAgentAuditLog implements AgentAuditLog {

    private static final String INSERT_SQL = """
            INSERT INTO harness_audit_log
                (agent_name, subject_type, subject_id, trace_id,
                 request_json, output_json, tool_calls_json, raw_llm_response,
                 pii_masked, fallback_reason, recorded_at)
            VALUES
                (:agentName, :subjectType, :subjectId, :traceId,
                 CAST(:requestJson AS JSONB), CAST(:outputJson AS JSONB),
                 CAST(:toolCallsJson AS JSONB), :rawLlmResponse,
                 :piiMasked, :fallbackReason, :recordedAt)
            """;

    private static final String FIND_LATEST_SQL = """
            SELECT agent_name, subject_type, subject_id, trace_id,
                   request_json, output_json, tool_calls_json, raw_llm_response,
                   pii_masked, fallback_reason, recorded_at
              FROM harness_audit_log
             WHERE subject_type = :subjectType AND subject_id = :subjectId
             ORDER BY recorded_at DESC, id DESC
             LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 호출자의 트랜잭션과 분리해 기록한다.
     *
     * <p>판단이 롤백되더라도 "그런 판단을 시도했다"는 사실은 남아야 한다. 같은 트랜잭션에
     * 두면 실패한 판단이 통째로 사라져, 사후에 무슨 일이 있었는지 재구성할 수 없다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AgentAuditEntry entry) {
        jdbc.update(INSERT_SQL, params(entry));
        log.debug("[audit] agent={} subject={}:{} trace={} 기록",
                entry.agentName(), entry.subjectType(), entry.subjectId(), entry.traceId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentAuditEntry> findLatest(String subjectType, String subjectId) {
        List<AgentAuditEntry> rows = jdbc.query(FIND_LATEST_SQL,
                new MapSqlParameterSource()
                        .addValue("subjectType", subjectType)
                        .addValue("subjectId", subjectId),
                (rs, i) -> new AgentAuditEntry(
                        rs.getString("agent_name"),
                        rs.getString("subject_type"),
                        rs.getString("subject_id"),
                        rs.getString("trace_id"),
                        rs.getString("request_json"),
                        rs.getString("output_json"),
                        rs.getString("tool_calls_json"),
                        rs.getString("raw_llm_response"),
                        rs.getBoolean("pii_masked"),
                        rs.getString("fallback_reason"),
                        rs.getTimestamp("recorded_at").toInstant()));
        return rows.stream().findFirst();
    }

    private static MapSqlParameterSource params(AgentAuditEntry e) {
        return new MapSqlParameterSource()
                .addValue("agentName", e.agentName())
                .addValue("subjectType", e.subjectType())
                .addValue("subjectId", e.subjectId())
                .addValue("traceId", e.traceId())
                .addValue("requestJson", e.requestJson())
                .addValue("outputJson", e.outputJson())
                .addValue("toolCallsJson", e.toolCallsJson())
                .addValue("rawLlmResponse", e.rawLlmResponse())
                .addValue("piiMasked", e.piiMasked())
                .addValue("fallbackReason", e.fallbackReason())
                .addValue("recordedAt", Timestamp.from(e.recordedAt()));
    }
}
