package com.bank.ai.rag.search;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkTest {

    @Test
    void promptText_summary_있으면_summary_반환() {
        var chunk = new Chunk(1L, "policy_regulation", "src1", "긴 원문...", "요약 한 줄", Map.of(), 0.9);
        assertThat(chunk.promptText()).isEqualTo("요약 한 줄");
    }

    @Test
    void promptText_summary_없으면_text_반환() {
        var chunk = new Chunk(1L, "policy_regulation", "src1", "원문 텍스트", null, Map.of(), 0.9);
        assertThat(chunk.promptText()).isEqualTo("원문 텍스트");
    }

    @Test
    void promptText_500자_초과_시_truncate() {
        String longText = "가".repeat(600);
        var chunk = new Chunk(1L, "policy_regulation", "src1", longText, null, Map.of(), 0.9);
        assertThat(chunk.promptText()).hasSize(501).endsWith("…");
    }

    @Test
    void promptText_빈_summary는_text로_fallback() {
        var chunk = new Chunk(1L, "policy_regulation", "src1", "원문", "  ", Map.of(), 0.9);
        assertThat(chunk.promptText()).isEqualTo("원문");
    }
}
