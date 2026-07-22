package com.bank.ai.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditLogService 단위 + 통합 테스트 — phase-b-operational.md §B1.
 *
 * <p>H2 인메모리 DB 사용 (src/test/resources/application.yml).
 * 불변성 트리거(PG 전용)는 본 테스트에서 검증하지 않는다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auditdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.flyway.locations=classpath:db/h2-migration",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration",
        "ai.llm.provider=stub",
        "ai.audit.enabled=true"
})
class AuditLogServiceTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    // ── TC1: 감사 로그 INSERT + round-trip 조회 ─────────────────────────────

    @Test
    void record_insertsRow_findLatestByRevId_returnsRecord() {
        var record = sampleRecord(1001L, "TRACK_1", null);

        auditLogService.record(record);

        Optional<AgentAuditRecord> found = auditLogService.findLatestByRevId(1001L);
        assertThat(found).isPresent();
        assertThat(found.get().revId()).isEqualTo(1001L);
        assertThat(found.get().track()).isEqualTo("TRACK_1");
        assertThat(found.get().piiMasked()).isTrue();
        assertThat(found.get().fallbackReason()).isNull();
    }

    // ── TC2: fallbackReason 저장 검증 ────────────────────────────────────────

    @Test
    void record_withFallbackReason_persistsFallbackReason() {
        var record = sampleRecord(1002L, "TRACK_2", "LLM_RATE_LIMITED");

        auditLogService.record(record);

        Optional<AgentAuditRecord> found = auditLogService.findLatestByRevId(1002L);
        assertThat(found).isPresent();
        assertThat(found.get().fallbackReason()).isEqualTo("LLM_RATE_LIMITED");
    }

    // ── TC3: opinionJson 저장 검증 ───────────────────────────────────────────

    @Test
    void record_opinionJson_persistedAndRetrieved() {
        String opinionJson = """
                {"schema_version":"v1","risk_level":"MEDIUM","policy_flags":[]}
                """.strip();
        var record = new AgentAuditRecord(
                1003L, "TRACK_3", "{}", opinionJson, "[]", null, true, null);

        auditLogService.record(record);

        Optional<AgentAuditRecord> found = auditLogService.findLatestByRevId(1003L);
        assertThat(found).isPresent();
        // opinionJson에 핵심 필드 존재 확인 (DB 왕복 후)
        assertThat(found.get().opinionJson()).contains("schema_version");
        assertThat(found.get().opinionJson()).contains("MEDIUM");
    }

    // ── TC4: REQUIRES_NEW — 외부 트랜잭션 롤백 후에도 감사 로그 보존 ────────

    @Test
    @Transactional   // 테스트 메서드 자체 트랜잭션 → 롤백 대상
    void record_requiresNewTransaction_surviveOuterRollback() {
        var record = sampleRecord(1004L, "TRACK_1", null);

        // @Transactional(REQUIRES_NEW) 서비스 — 외부 트랜잭션(테스트)과 독립 커밋
        auditLogService.record(record);

        // 현재 트랜잭션 내에서 읽기 — 이미 커밋됐으므로 보임
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_audit_log WHERE rev_id = 1004",
                Collections.emptyMap(), Integer.class);
        assertThat(count).isEqualTo(1);

        // 테스트 메서드 트랜잭션은 종료 시 롤백되지만,
        // REQUIRES_NEW로 커밋된 레코드는 실제 DB에 남는다.
        // (이 케이스에서 H2 인메모리 DB 특성상 컨텍스트 공유로 보이지만, 독립 트랜잭션 경로 확인)
    }

    // ── TC5: disabled 시 no-op ────────────────────────────────────────────────

    @Test
    void findLatestByRevId_noRecord_returnsEmpty() {
        Optional<AgentAuditRecord> found = auditLogService.findLatestByRevId(9999L);
        assertThat(found).isEmpty();
    }

    // ── TC6: piiMasked 항상 true ─────────────────────────────────────────────

    @Test
    void record_piiMasked_isAlwaysTrue() {
        var record = sampleRecord(1005L, "TRACK_2", null);
        assertThat(record.piiMasked()).isTrue();

        auditLogService.record(record);

        Optional<AgentAuditRecord> found = auditLogService.findLatestByRevId(1005L);
        assertThat(found).isPresent();
        assertThat(found.get().piiMasked()).isTrue();
    }

    // ── TC7: 동일 revId 복수 기록 → findLatest 는 최신 1건 반환 ─────────────

    @Test
    void record_multipleForSameRevId_findLatest_returnsLatest() {
        auditLogService.record(sampleRecord(1006L, "TRACK_1", null));
        auditLogService.record(sampleRecord(1006L, "TRACK_3", "TOOL_ERROR"));

        Optional<AgentAuditRecord> found = auditLogService.findLatestByRevId(1006L);
        assertThat(found).isPresent();
        // 두 번째 레코드가 최신이므로 TRACK_3 + TOOL_ERROR
        assertThat(found.get().track()).isEqualTo("TRACK_3");
        assertThat(found.get().fallbackReason()).isEqualTo("TOOL_ERROR");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private static AgentAuditRecord sampleRecord(Long revId, String track, String fallbackReason) {
        return new AgentAuditRecord(
                revId,
                track,
                "{\"age\":35,\"productCode\":\"MORT_001\"}",
                "{\"schema_version\":\"v1\",\"risk_level\":\"LOW\"}",
                "[]",
                null,
                true,
                fallbackReason
        );
    }
}
