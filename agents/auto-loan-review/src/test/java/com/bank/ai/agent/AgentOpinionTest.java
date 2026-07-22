package com.bank.ai.agent;

import com.bank.ai.rule.domain.Track;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentOpinion record + RiskLevel + FallbackReason JSON 직렬화 검증.
 *
 * <p>loan_review.agent_opinion_json 에 저장될 스키마를 검증한다.
 */
class AgentOpinionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 정상_경로_JSON_스키마_검증() throws Exception {
        var opinion = AgentOpinion.of(
                0.72, 0.18, RiskLevel.MEDIUM,
                List.of("DSR_THRESHOLD_WARNING"),
                "상환 부담 증가로 위험도 상승",
                List.of(new SimulationResult(
                        "loan_amount_reduction_20pct",
                        80_000_000L, 60,
                        0.84, 0.12,
                        "risk_reduced", "대출 금액 20% 감소 시 승인 가능성 증가",
                        false
                )),
                false
        );

        String json = objectMapper.writeValueAsString(opinion);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("schema_version").asText()).isEqualTo("v1");
        assertThat(node.get("decision_score").asDouble()).isEqualTo(0.72);
        assertThat(node.get("pd_score").asDouble()).isEqualTo(0.18);
        assertThat(node.get("risk_level").asText()).isEqualTo("MEDIUM");
        assertThat(node.get("policy_flags").get(0).asText()).isEqualTo("DSR_THRESHOLD_WARNING");
        assertThat(node.get("fallback_reason").isNull()).isTrue();
        assertThat(node.get("disagreement").asBoolean()).isFalse();

        // simulation_results 구조 검증
        JsonNode sim = node.get("simulation_results").get(0);
        assertThat(sim.get("scenario").asText()).isEqualTo("loan_amount_reduction_20pct");
        assertThat(sim.get("still_violates").asBoolean()).isFalse();
        assertThat(sim.get("mutated_amount_kw").asLong()).isEqualTo(80_000_000L);
    }

    @ParameterizedTest
    @EnumSource(FallbackReason.class)
    void fallback_경로는_null_스코어_및_사유코드_직렬화(FallbackReason reason) throws Exception {
        var opinion = AgentOpinion.fallback(reason);

        String json = objectMapper.writeValueAsString(opinion);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("schema_version").asText()).isEqualTo("v1");
        assertThat(node.get("fallback_reason").asText()).isEqualTo(reason.name());
        assertThat(node.get("decision_score").isNull()).isTrue();
        assertThat(node.get("pd_score").isNull()).isTrue();
        assertThat(node.get("simulation_results").isEmpty()).isTrue();
        assertThat(node.get("reasoning_summary").asText()).isNotBlank();
    }

    @Test
    void RiskLevel_Track에서_파생() {
        assertThat(RiskLevel.from(Track.TRACK_1)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.from(Track.TRACK_2)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.from(Track.TRACK_3)).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void PreReviewAgentService_killswitch_반환_확인() {
        var props = new com.bank.ai.llm.config.AgentProperties(false, 6, 2, true, 0);
        // enabled=false → 즉시 반환, 나머지 의존성 미사용
        var service = new PreReviewAgentService(props, null, null, null, null, null, null, null, null, null);

        var result = service.run(1L, null, null);

        assertThat(result.fallbackReason()).isEqualTo(FallbackReason.AGENT_DISABLED);
        assertThat(result.schemaVersion()).isEqualTo("v1");
    }
}
