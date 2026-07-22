package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ShadowModeService 통합 테스트 — phase-b-operational.md §B3.
 *
 * <p>H2 인메모리 DB + ai.shadow.enabled=true 로 shadow_run_result INSERT 검증.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shadowdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.locations=classpath:db/h2-migration",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration",
        "ai.llm.provider=stub",
        "ai.shadow.enabled=true",
        "ai.shadow.model=stub-v1",
        "ai.shadow.prompt-version=v1"
})
class ShadowModeServiceTest {

    @Autowired
    private ShadowModeService shadowModeService;

    @MockBean
    private PreReviewAgentService preReviewAgentService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private static final TrackDecision DECISION =
            new TrackDecision(Track.TRACK_3, List.of(), 0.35, 0.65, 0.347, 0.104, "회색지대");

    private static final AgentOpinion PROD_OPINION =
            AgentOpinion.of(0.65, 0.35, RiskLevel.MEDIUM, List.of(), "프로덕션 요약", List.of(), false);

    // ── TC 1: shadow run → shadow_run_result INSERT ──────────────────────

    @Test
    void runShadow_insertsRow() {
        AgentOpinion shadowOpinion =
                AgentOpinion.of(0.65, 0.35, RiskLevel.MEDIUM, List.of(), "shadow 요약", List.of(), false);
        when(preReviewAgentService.run(any(), any(), any())).thenReturn(shadowOpinion);

        shadowModeService.runShadow(100L, stubRequest(), DECISION, PROD_OPINION);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shadow_run_result WHERE rev_id = 100",
                    Collections.emptyMap(), Integer.class);
            assertThat(count).isEqualTo(1);
        });
    }

    // ── TC 2: 동일 의견 → diverged=false ────────────────────────────────

    @Test
    void runShadow_sameOpinion_divergedFalse() {
        when(preReviewAgentService.run(any(), any(), any())).thenReturn(PROD_OPINION);

        shadowModeService.runShadow(101L, stubRequest(), DECISION, PROD_OPINION);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Boolean diverged = jdbc.queryForObject(
                    "SELECT diverged FROM shadow_run_result WHERE rev_id = 101",
                    Collections.emptyMap(), Boolean.class);
            assertThat(diverged).isFalse();
        });
    }

    // ── TC 3: 다른 riskLevel → diverged=true ────────────────────────────

    @Test
    void runShadow_differentRiskLevel_divergedTrue() {
        AgentOpinion shadowOpinion =
                AgentOpinion.of(0.65, 0.35, RiskLevel.HIGH, List.of(), "shadow 요약", List.of(), false);
        when(preReviewAgentService.run(any(), any(), any())).thenReturn(shadowOpinion);

        shadowModeService.runShadow(102L, stubRequest(), DECISION, PROD_OPINION);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Boolean diverged = jdbc.queryForObject(
                    "SELECT diverged FROM shadow_run_result WHERE rev_id = 102",
                    Collections.emptyMap(), Boolean.class);
            assertThat(diverged).isTrue();
        });
    }

    // ── TC 4-E: E4-2 rag_backend 컬럼 — 기본값 'inline' 저장 ────────────

    @Test
    void runShadow_storesRagBackend_defaultInline() {
        AgentOpinion shadowOpinion =
                AgentOpinion.of(0.65, 0.35, RiskLevel.MEDIUM, List.of(), "shadow 요약", List.of(), false);
        when(preReviewAgentService.run(any(), any(), any())).thenReturn(shadowOpinion);

        shadowModeService.runShadow(104L, stubRequest(), DECISION, PROD_OPINION);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            String ragBackend = jdbc.queryForObject(
                    "SELECT rag_backend FROM shadow_run_result WHERE rev_id = 104",
                    Collections.emptyMap(), String.class);
            assertThat(ragBackend).isEqualTo("inline");
        });
    }

    // ── TC 5: shadow 실패 → prod 영향 없음 ──────────────────────────────

    @Test
    void runShadow_agentThrows_doesNotPropagateException() {
        when(preReviewAgentService.run(any(), any(), any()))
                .thenThrow(new RuntimeException("shadow agent error"));

        // 예외가 전파되지 않아야 함 (shadow 실패는 silent)
        shadowModeService.runShadow(103L, stubRequest(), DECISION, PROD_OPINION);

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM shadow_run_result WHERE rev_id = 103",
                    Collections.emptyMap(), Integer.class);
            assertThat(count).isEqualTo(0);  // INSERT 안 됨
        });
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static AutoReviewRequest stubRequest() {
        return new AutoReviewRequest(
                999L, null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 750,
                "MORT_001", 200_000_000L, 360, "아파트 구입", null,
                null, null, null, null, null, null, null
        );
    }
}
