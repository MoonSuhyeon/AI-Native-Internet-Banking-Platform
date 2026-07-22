package com.bank.ai.drift;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * FairnessReportService 통합 테스트 — PostgreSQL Testcontainers + 2040년 3월 데이터.
 * 날짜 격리: 2040년 전용.
 *
 * <p>H2 는 PostgreSQL `ON CONFLICT ... DO UPDATE` 업서트를 파싱하지 못하므로
 * 운영과 동일한 Postgres 에서 검증한다 (db/drift-pg-migration 스키마).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.flyway.locations=classpath:db/drift-pg-migration",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration",
        "ai.llm.provider=stub",
        "ai.drift.enabled=true",
        "ai.drift.model-version=v1",
        "ai.drift.fairness-gap-threshold=0.05",
        "spring.batch.job.enabled=false"
})
class FairnessReportServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");   // JSONB 컬럼에 JSON 문자열 파라미터 바인딩 허용

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private FairnessReportService fairnessReportService;

    @Autowired
    private FairnessReportRepository fairnessReportRepo;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    /**
     * 30대(승인률 1.0) + 60대+(승인률 0.0) → 전체 0.5 → gap ±0.5 → 둘 다 flagged=true.
     */
    @Test
    void generateMonthlyReport_flagsBothGroupsWithLargeGap() {
        // 30대 그룹 10건 — LOW risk (승인)
        for (int i = 0; i < 10; i++) {
            insertAuditLog(50000L + i, 35,  "LOW",  "2040-03-" + String.format("%02d", i + 1));
        }
        // 60대+ 그룹 10건 — HIGH risk (미승인)
        for (int i = 0; i < 10; i++) {
            insertAuditLog(51000L + i, 65, "HIGH", "2040-03-" + String.format("%02d", i + 1));
        }

        YearMonth month = YearMonth.of(2040, 3);
        List<FairnessReport> reports = fairnessReportService.generateMonthlyReport(month);

        assertThat(reports).isNotEmpty();

        FairnessReport thirties = reports.stream()
            .filter(r -> r.groupKey().equals("age_band:30s"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("30s 그룹 없음"));
        assertThat(thirties.approvalRate()).isEqualTo(1.0);
        assertThat(thirties.flagged()).isTrue();

        FairnessReport sixtyPlus = reports.stream()
            .filter(r -> r.groupKey().equals("age_band:60plus"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("60plus 그룹 없음"));
        assertThat(sixtyPlus.approvalRate()).isEqualTo(0.0);
        assertThat(sixtyPlus.flagged()).isTrue();
    }

    @Test
    void fairnessRepo_upsert_idempotentOnDuplicateMonthAndGroup() {
        LocalDate month = LocalDate.of(2041, 1, 1);
        FairnessReport first  = new FairnessReport(month, "age_band:30s", 0.8, 50, 0.6, 0.2,  true);
        FairnessReport second = new FairnessReport(month, "age_band:30s", 0.9, 60, 0.65, 0.25, true);

        fairnessReportRepo.insert(first);
        fairnessReportRepo.insert(second);

        List<FairnessReport> flagged = fairnessReportRepo.findFlaggedByMonth(month);
        assertThat(flagged).hasSize(1);
        assertThat(flagged.get(0).approvalRate()).isCloseTo(0.9, within(1e-9));
        assertThat(flagged.get(0).sampleCount()).isEqualTo(60);
    }

    private void insertAuditLog(long revId, int age, String riskLevel, String date) {
        jdbc.update(
            """
            INSERT INTO agent_audit_log
                (rev_id, track, request_snapshot, opinion_json, tool_calls_json, created_at)
            VALUES (:revId, 'TRACK_1', :req, :opin, '[]', CAST(:ts AS TIMESTAMP WITH TIME ZONE))
            """,
            new MapSqlParameterSource()
                .addValue("revId", revId)
                .addValue("req", "{\"age\": " + age + ", \"creditScore\": 650}")
                .addValue("opin", "{\"risk_level\": \"" + riskLevel + "\"}")
                .addValue("ts", date + "T10:00:00")
        );
    }
}
