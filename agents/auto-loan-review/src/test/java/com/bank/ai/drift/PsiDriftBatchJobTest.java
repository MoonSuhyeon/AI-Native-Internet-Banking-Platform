package com.bank.ai.drift;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * PsiDriftBatchJob 통합 테스트 — PostgreSQL Testcontainers + 직전 주 데이터.
 *
 * <p>H2 는 PostgreSQL `ON CONFLICT ... DO UPDATE` 업서트를 파싱하지 못하므로
 * 운영과 동일한 Postgres 에서 검증한다 (db/drift-pg-migration 스키마).
 * Spring Batch 메타테이블은 Postgres 에 자동 생성(initialize-schema=always).
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.flyway.locations=classpath:db/drift-pg-migration",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration",
        "ai.llm.provider=stub",
        "ai.drift.enabled=true",
        "ai.drift.model-version=v1",
        "spring.batch.job.enabled=false"
})
class PsiDriftBatchJobTest {

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
    private JobLauncher jobLauncher;

    @Autowired
    private Job psiDriftJob;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private PsiDriftResultRepository resultRepo;

    @Test
    void psiDriftJob_insertsResult_whenDataAndBaselineExist() throws Exception {
        // 2040-01-07 ~ 2040-01-13 (직전 주) 데이터 삽입
        // PsiDriftBatchJob은 LocalDate.now().with(MONDAY) 기준 직전 1주치를 읽는다.
        // 테스트에서는 배치 잡 실행 전 "지난 주" 범위에 해당하는 2040-01-07~2040-01-13 데이터를 넣고,
        // 배치를 기동 시 LocalDate.now() 가 실제 오늘(2026-05-26)이므로
        // weekStart = 2026-05-18, weekEnd = 2026-05-25 → 이 범위 데이터를 넣어야 한다.
        LocalDate calcWeek  = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        LocalDate weekStart = calcWeek.minusWeeks(1);
        LocalDate weekEnd   = calcWeek;

        // agent_audit_log에 테스트 데이터 삽입 (weekStart ~ weekEnd 범위)
        for (int i = 0; i < 5; i++) {
            LocalDate day = weekStart.plusDays(i);
            jdbc.update(
                """
                INSERT INTO agent_audit_log
                    (rev_id, track, request_snapshot, opinion_json, tool_calls_json, created_at)
                VALUES (:revId, 'TRACK_1', :req, :opin, '[]', CAST(:ts AS TIMESTAMP WITH TIME ZONE))
                """,
                new MapSqlParameterSource()
                    .addValue("revId", 40000L + i)
                    .addValue("req", "{\"creditScore\": " + (500 + i * 50) + ", \"age\": 35}")
                    .addValue("opin", "{\"risk_level\": \"LOW\"}")
                    .addValue("ts", day.atTime(10, 0).toString())
            );
        }

        // psi_baseline 시드 (creditScore, 5 버킷)
        List<double[]> buckets = List.of(
            new double[]{300, 450},
            new double[]{450, 550},
            new double[]{550, 650},
            new double[]{650, 750},
            new double[]{750, 900}
        );
        double[] baseRatios = {0.10, 0.20, 0.40, 0.20, 0.10};
        for (int i = 0; i < buckets.size(); i++) {
            jdbc.update(
                """
                INSERT INTO psi_baseline
                    (feature_name, bucket_index, bucket_low, bucket_high, baseline_ratio, baseline_date, model_version)
                VALUES (:fn, :bi, :bl, :bh, :br, :bd, :mv)
                """,
                new MapSqlParameterSource()
                    .addValue("fn", "creditScore")
                    .addValue("bi", i)
                    .addValue("bl", buckets.get(i)[0])
                    .addValue("bh", buckets.get(i)[1])
                    .addValue("br", baseRatios[i])
                    .addValue("bd", weekStart.withDayOfMonth(1))
                    .addValue("mv", "v1")
            );
        }

        // 배치 잡 실행
        jobLauncher.run(psiDriftJob, new JobParameters());

        // psi_drift_result 1건 확인
        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM psi_drift_result WHERE feature_name = :fn AND model_version = :mv",
            Map.of("fn", "creditScore", "mv", "v1"),
            Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void resultRepo_upsert_idempotentOnDuplicateCalcWeek() {
        LocalDate calcWeek = LocalDate.of(2041, 1, 6);
        PsiDriftReport first  = new PsiDriftReport("creditScore", 0.05, PsiStatus.STABLE,   100, "v1");
        PsiDriftReport second = new PsiDriftReport("creditScore", 0.25, PsiStatus.CRITICAL, 200, "v1");

        resultRepo.insert(first,  calcWeek);
        resultRepo.insert(second, calcWeek);

        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM psi_drift_result WHERE feature_name='creditScore' AND calc_week=:w AND model_version='v1'",
            Map.of("w", calcWeek), Integer.class);
        assertThat(count).isEqualTo(1);

        PsiDriftReport saved = resultRepo.findLatest("creditScore", "v1").orElseThrow();
        assertThat(saved.psiValue()).isCloseTo(0.25, within(1e-9));
        assertThat(saved.status()).isEqualTo(PsiStatus.CRITICAL);
        assertThat(saved.sampleCount()).isEqualTo(200);
    }
}
