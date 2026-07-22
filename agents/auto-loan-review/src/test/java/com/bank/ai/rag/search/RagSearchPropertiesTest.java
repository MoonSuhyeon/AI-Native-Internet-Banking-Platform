package com.bank.ai.rag.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class RagSearchPropertiesTest {

    @Test
    void 정상_파라미터는_생성_성공() {
        assertThatNoException().isThrownBy(() ->
                new RagSearchProperties(0.7, 0.5, 5));
    }

    @Test
    void alpha_음수면_예외() {
        assertThatThrownBy(() -> new RagSearchProperties(-0.1, 0.5, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alpha");
    }

    @Test
    void alpha_1초과면_예외() {
        assertThatThrownBy(() -> new RagSearchProperties(1.1, 0.5, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void similarityThreshold_음수면_예외() {
        assertThatThrownBy(() -> new RagSearchProperties(0.7, -0.1, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("similarityThreshold");
    }

    @Test
    void defaultK_0이하면_예외() {
        assertThatThrownBy(() -> new RagSearchProperties(0.7, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("defaultK");
    }
}
