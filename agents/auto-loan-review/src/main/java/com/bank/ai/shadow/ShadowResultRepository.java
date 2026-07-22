package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.rule.domain.Track;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * shadow_run_result 테이블 접근 — NamedParameterJdbcTemplate 기반.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ShadowResultRepository {

    private static final String INSERT_SQL = """
            INSERT INTO shadow_run_result
                (rev_id, prod_opinion_json, shadow_opinion_json, diverged, diverge_reasons,
                 prod_track, shadow_track, prod_decision_score, shadow_decision_score,
                 shadow_model, shadow_prompt_version, rag_enabled, rag_backend)
            VALUES
                (:revId, CAST(:prodOpinionJson AS VARCHAR), CAST(:shadowOpinionJson AS VARCHAR),
                 :diverged, :divergeReasons,
                 :prodTrack, :shadowTrack, :prodDecisionScore, :shadowDecisionScore,
                 :shadowModel, :shadowPromptVersion, :ragEnabled, :ragBackend)
            """;

    private static final String COUNT_DIVERGED_SQL = """
            SELECT COUNT(*) FROM shadow_run_result
            WHERE diverged = TRUE
              AND created_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND created_at <  CAST(:to   AS TIMESTAMP WITH TIME ZONE)
            """;

    private static final String TOTAL_COUNT_SQL = """
            SELECT COUNT(*) FROM shadow_run_result
            WHERE created_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND created_at <  CAST(:to   AS TIMESTAMP WITH TIME ZONE)
            """;

    /**
     * 기간 내 일치율 = (total - diverged) / total.
     * total=0 이면 1.0 반환 (데이터 없음 → 게이트 단계에서 INSUFFICIENT_DATA 처리).
     */
    private static final String AGREEMENT_RATE_SQL = """
            SELECT CASE WHEN COUNT(*) = 0 THEN 1.0
                        ELSE CAST(COUNT(*) FILTER (WHERE NOT diverged) AS DOUBLE PRECISION)
                             / COUNT(*)
                   END
            FROM shadow_run_result
            WHERE created_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND created_at <  CAST(:to   AS TIMESTAMP WITH TIME ZONE)
            """;

    /**
     * citation 누락률 = POLICY_FLAG_DIFF 발생 건수 / rag_enabled=true 건수.
     * rag_enabled 건수=0 이면 0.0 반환.
     */
    private static final String CITATION_MISS_RATE_SQL = """
            SELECT CASE WHEN COUNT(*) FILTER (WHERE rag_enabled) = 0 THEN 0.0
                        ELSE CAST(COUNT(*) FILTER (WHERE rag_enabled
                                  AND diverge_reasons LIKE '%POLICY_FLAG_DIFF%') AS DOUBLE PRECISION)
                             / COUNT(*) FILTER (WHERE rag_enabled)
                   END
            FROM shadow_run_result
            WHERE created_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND created_at <  CAST(:to   AS TIMESTAMP WITH TIME ZONE)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** shadow 비교 결과 INSERT. */
    public void insert(ShadowComparisonResult result) {
        try {
            String prodJson   = serialize(result.prodOpinion());
            String shadowJson = serialize(result.shadowOpinion());
            String reasonsJson = objectMapper.writeValueAsString(result.divergeReasons());

            var params = new MapSqlParameterSource()
                    .addValue("revId",              result.revId())
                    .addValue("prodOpinionJson",     prodJson)
                    .addValue("shadowOpinionJson",   shadowJson)
                    .addValue("diverged",            result.diverged())
                    .addValue("divergeReasons",      reasonsJson)
                    .addValue("prodTrack",           result.track().name())
                    .addValue("shadowTrack",         result.track().name())
                    .addValue("prodDecisionScore",   decisionScore(result.prodOpinion()))
                    .addValue("shadowDecisionScore", decisionScore(result.shadowOpinion()))
                    .addValue("shadowModel",         result.shadowModel())
                    .addValue("shadowPromptVersion", result.shadowPromptVersion())
                    .addValue("ragEnabled",          result.ragEnabled())
                    .addValue("ragBackend",          result.ragBackend());

            jdbc.update(INSERT_SQL, params);
        } catch (JsonProcessingException e) {
            log.error("[Shadow] INSERT 직렬화 실패 revId={}", result.revId(), e);
            throw new RuntimeException("shadow_run_result 직렬화 오류", e);
        }
    }

    /** 기간 내 diverged=true 건수 (어드민/리포트용). */
    public int countDiverged(LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject(
                COUNT_DIVERGED_SQL,
                Map.of("from", from.atStartOfDay().toString(),
                       "to",   to.plusDays(1).atStartOfDay().toString()),
                Integer.class);
        return count != null ? count : 0;
    }

    /** 기간 내 shadow_run_result 총 건수 (게이트 판정용). */
    public int totalCount(LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject(
                TOTAL_COUNT_SQL,
                Map.of("from", from.atStartOfDay().toString(),
                       "to",   to.plusDays(1).atStartOfDay().toString()),
                Integer.class);
        return count != null ? count : 0;
    }

    /**
     * 기간 내 shadow 일치율 (E4-4 게이트용).
     *
     * @return (1 - diverged/total). total=0 이면 1.0.
     */
    public double agreementRate(LocalDate from, LocalDate to) {
        Double rate = jdbc.queryForObject(
                AGREEMENT_RATE_SQL,
                Map.of("from", from.atStartOfDay().toString(),
                       "to",   to.plusDays(1).atStartOfDay().toString()),
                Double.class);
        return rate != null ? rate : 1.0;
    }

    /**
     * 기간 내 citation 누락률 (E4-4 게이트용).
     *
     * @return POLICY_FLAG_DIFF 건수 / rag_enabled 건수. rag_enabled 건수=0 이면 0.0.
     */
    public double citationMissRate(LocalDate from, LocalDate to) {
        Double rate = jdbc.queryForObject(
                CITATION_MISS_RATE_SQL,
                Map.of("from", from.atStartOfDay().toString(),
                       "to",   to.plusDays(1).atStartOfDay().toString()),
                Double.class);
        return rate != null ? rate : 0.0;
    }

    // ─────────────────────────────────────────────────────────────────────

    private String serialize(AgentOpinion opinion) throws JsonProcessingException {
        return objectMapper.writeValueAsString(opinion);
    }

    private static Double decisionScore(AgentOpinion opinion) {
        return opinion.decisionScore();
    }
}
