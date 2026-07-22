package com.bank.ai.llm.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PromptRegistry 단위 테스트 — plan/llm-pipeline.md §6.2.
 *
 * <p>실제 YAML 파일을 classpath 에서 로드해 id·version·system prompt 정합성,
 * 미등록 id 조회 시 예외, {@link ReviewReportService#trackPromptId} 와의 일관성까지 검증.
 */
class PromptRegistryTest {

    private PromptRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        registry = new PromptRegistry();
        registry.load();
    }

    @Test
    void purpose_analysis_v1_로드_성공() {
        var p = registry.get("purpose_analysis", 1);

        assertThat(p.id()).isEqualTo("purpose_analysis");
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.system()).isNotBlank();
        assertThat(p.system()).contains("plausibility");
        assertThat(p.system()).contains("user_content");
        assertThat(p.maxTokens()).isGreaterThan(0);
        assertThat(p.changelog()).isNotEmpty();
    }

    @Test
    void review_report_track1_v1_로드_성공() {
        var p = registry.get("review_report_track1", 1);

        assertThat(p.id()).isEqualTo("review_report_track1");
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.system()).contains("TRACK_1");
        assertThat(p.system()).contains("user_content");
    }

    @Test
    void review_report_track2_v1_로드_성공() {
        var p = registry.get("review_report_track2", 1);

        assertThat(p.id()).isEqualTo("review_report_track2");
        assertThat(p.system()).contains("TRACK_2");
        // Track 2 는 citations 최소 2개 요건 명시 검증
        assertThat(p.system()).contains("citations");
    }

    @Test
    void review_report_track3_v1_로드_성공() {
        var p = registry.get("review_report_track3", 1);

        assertThat(p.id()).isEqualTo("review_report_track3");
        assertThat(p.system()).contains("TRACK_3");
    }

    @Test
    void 총_8개_프롬프트_등록() {
        assertThat(registry.all()).hasSize(8);
    }

    @Test
    void 미등록_id_조회시_예외() {
        assertThatThrownBy(() -> registry.get("non_existent", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("미등록 프롬프트");
    }

    @Test
    void 등록된_id이지만_version_불일치시_예외() {
        assertThatThrownBy(() -> registry.get("purpose_analysis", 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("미등록 프롬프트");
    }

    @Test
    void trackPromptId_와_registry_키가_일치한다() {
        // ReviewReportService.trackPromptId() 로 생성한 id 가 YAML id 와 일치하는지 검증
        assertThat(registry.all()).containsKey("review_report_track1:1");
        assertThat(registry.all()).containsKey("review_report_track2:1");
        assertThat(registry.all()).containsKey("review_report_track3:1");
    }

    // ── v2 프롬프트 TC ────────────────────────────────────────────────────

    @Test
    void review_report_track1_v2_로드_성공_rag_지침_포함() {
        var p = registry.get("review_report_track1", 2);

        assertThat(p.id()).isEqualTo("review_report_track1");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.system()).contains("TRACK_1");
        assertThat(p.system()).contains("rag_policy_context");
        assertThat(p.changelog()).hasSize(2);
    }

    @Test
    void review_report_track2_v2_로드_성공_rag_지침_포함() {
        var p = registry.get("review_report_track2", 2);

        assertThat(p.id()).isEqualTo("review_report_track2");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.system()).contains("TRACK_2");
        assertThat(p.system()).contains("rag_policy_context");
        assertThat(p.system()).contains("citations");
        assertThat(p.maxTokens()).isEqualTo(768);
    }

    @Test
    void review_report_track3_v2_로드_성공_rag_지침_포함() {
        var p = registry.get("review_report_track3", 2);

        assertThat(p.id()).isEqualTo("review_report_track3");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.system()).contains("TRACK_3");
        assertThat(p.system()).contains("rag_policy_context");
    }

    @Test
    void rejection_reason_draft_v2_로드_성공_rag_지침_포함() {
        var p = registry.get("rejection_reason_draft", 2);

        assertThat(p.id()).isEqualTo("rejection_reason_draft");
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.system()).contains("TRACK_2");
        assertThat(p.system()).contains("rag_policy_context");
        assertThat(p.system()).contains("citations");
        assertThat(p.maxTokens()).isEqualTo(768);
    }

    @Test
    void maxTokens_와_temperature_YAML에서_정상_파싱() {
        // Track 2 는 max_tokens=768 (Track 1/3 과 다름)
        var track2 = registry.get("review_report_track2", 1);
        assertThat(track2.maxTokens()).isEqualTo(768);
        assertThat(track2.temperature()).isEqualTo(0.0);
    }
}
