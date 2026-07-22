package com.bank.ai.rule.service;

import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.config.RuleEngineProperties.HardConstraints;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PolicyMatrixTest {

    private static final RuleEngineProperties PROPS = new RuleEngineProperties(
            new HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30,
            0.50,
            Map.of("MORT_001", Map.of(
                    "regular", 0.347,
                    "precarious", 0.991,
                    "senior", 0.788
            )),
            0.95, 0.20,
            true, false
    );

    private final PolicyMatrix matrix = new PolicyMatrix(PROPS);

    @Test
    void 매트릭스_cell_hit시_정의된_값_반환() {
        assertThat(matrix.lookup("MORT_001", "regular")).isEqualTo(0.347);
        assertThat(matrix.lookup("MORT_001", "precarious")).isEqualTo(0.991);
    }

    @Test
    void 상품_miss시_default_반환() {
        assertThat(matrix.lookup("CRED_001", "regular")).isEqualTo(0.50);
    }

    @Test
    void 상품은_있지만_세그먼트_miss시_default_반환() {
        assertThat(matrix.lookup("MORT_001", "young")).isEqualTo(0.50);
    }

    @Test
    void 안전여유_임계는_lookup_곱하기_ratio() {
        // regular: 0.347 × 0.30 = 0.1041
        assertThat(matrix.safetyMarginThreshold("MORT_001", "regular"))
                .isEqualTo(0.347 * 0.30, within(1e-9));
        // miss cell 도 default × ratio = 0.15
        assertThat(matrix.safetyMarginThreshold("CRED_001", "regular"))
                .isEqualTo(0.50 * 0.30, within(1e-9));
    }
}
