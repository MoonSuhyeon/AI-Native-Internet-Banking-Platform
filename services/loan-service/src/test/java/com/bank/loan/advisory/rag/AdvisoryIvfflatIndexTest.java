package com.bank.loan.advisory.rag;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4 — ivfflat 인덱스 존재 검증 + EXPLAIN 플랜 캡쳐.
 * 테스트 연도 격리: 해당 없음 (스키마 메타 조회, 데이터 INSERT 없음).
 *
 * 완료 조건:
 *   - 두 임베딩 인덱스가 ivfflat 으로 존재
 *   - EXPLAIN (FORMAT JSON) 결과 로그로 캡쳐 (플랜 종류는 rows 에 따라 가변)
 */
class AdvisoryIvfflatIndexTest extends AbstractLoanIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryIvfflatIndexTest.class);

    @Autowired
    JdbcTemplate jdbcTemplate;

    // ── 인덱스 존재 확인 ─────────────────────────────────────────────────────

    @Test
    void ivfflat_임베딩_인덱스_두_개_존재() {
        List<String> indices = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes " +
                "WHERE tablename IN ('advisory_document_chunk', 'advisory_case_index') " +
                "  AND indexname LIKE '%embedding%' " +
                "ORDER BY indexname",
                String.class);

        assertThat(indices).containsExactlyInAnyOrder(
                "idx_advisory_document_chunk_embedding",
                "idx_advisory_case_index_embedding");
    }

    @Test
    void ivfflat_인덱스_접근_방식이_ivfflat_임을_pg_indexes_로_확인() {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes " +
                "WHERE tablename IN ('advisory_document_chunk', 'advisory_case_index') " +
                "  AND indexname LIKE '%embedding%' " +
                "ORDER BY indexname",
                String.class);

        assertThat(definitions).hasSize(2);
        assertThat(definitions).allSatisfy(def ->
                assertThat(def).containsIgnoringCase("ivfflat"));
    }

    // ── EXPLAIN 플랜 캡쳐 (결과는 rows 에 따라 가변 — assert 없이 로그로만 기록) ──

    @Test
    void explain_advisory_case_index_cosine_검색_플랜_캡쳐() {
        String plan = jdbcTemplate.queryForObject(
                "EXPLAIN (FORMAT JSON) " +
                "SELECT case_idx_id FROM advisory_case_index " +
                "ORDER BY embedding <=> (array_fill(0::float4, ARRAY[1536]))::vector " +
                "LIMIT 5",
                String.class);

        log.info("[Stage4-verify] advisory_case_index cosine EXPLAIN:\n{}", plan);
        assertThat(plan).isNotNull().isNotBlank();
    }

    @Test
    void explain_advisory_document_chunk_cosine_검색_플랜_캡쳐() {
        String plan = jdbcTemplate.queryForObject(
                "EXPLAIN (FORMAT JSON) " +
                "SELECT chunk_id FROM advisory_document_chunk " +
                "ORDER BY embedding <=> (array_fill(0::float4, ARRAY[1536]))::vector " +
                "LIMIT 5",
                String.class);

        log.info("[Stage4-verify] advisory_document_chunk cosine EXPLAIN:\n{}", plan);
        assertThat(plan).isNotNull().isNotBlank();
    }
}
