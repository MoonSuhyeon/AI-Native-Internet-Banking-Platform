package com.bank.ai.llm.report;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyIndexTest {

    @Test
    void exists는_매핑_된_id에만_true() {
        var idx = new InlinePolicyIndex(Map.of(
                "A_V1", new PolicyIndex.PolicyEntry("정책 A", "src-1"),
                "B_V1", new PolicyIndex.PolicyEntry("정책 B", "src-2")
        ));

        assertThat(idx.exists("A_V1")).isTrue();
        assertThat(idx.exists("B_V1")).isTrue();
        assertThat(idx.exists("missing")).isFalse();
    }

    @Test
    void null_inline_빈_map으로_초기화() {
        var idx = new InlinePolicyIndex(null);

        assertThat(idx.exists("anything")).isFalse();
        assertThat(idx.inline()).isEmpty();
    }

    @Test
    void get은_PolicyEntry_반환() {
        var idx = new InlinePolicyIndex(Map.of(
                "A_V1", new PolicyIndex.PolicyEntry("정책 A", "src-1")
        ));

        var entry = idx.get("A_V1").orElseThrow();
        assertThat(entry.text()).isEqualTo("정책 A");
        assertThat(entry.source()).isEqualTo("src-1");
    }
}
