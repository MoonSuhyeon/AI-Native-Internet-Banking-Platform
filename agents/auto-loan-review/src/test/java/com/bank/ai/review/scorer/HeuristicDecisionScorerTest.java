package com.bank.ai.review.scorer;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@link HeuristicDecisionScorer} 단위 테스트.
 *
 * <p>외부 의존(inference-server, DB, Kafka) 없음 — 순수 로직 검증.
 */
class HeuristicDecisionScorerTest {

    private HeuristicDecisionScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new HeuristicDecisionScorer();
    }

    // ------------------------------------------------------------------ //
    //  score() 통합 흐름                                                   //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("우량 고객 → APPROVE, pdScore 낮음")
    void score_primeApplicant_approve() {
        AutoReviewRequest req = primeRequest();

        AutoReviewResponse res = scorer.score(req);

        assertThat(res.modelVersion()).isEqualTo(HeuristicDecisionScorer.MODEL_VERSION);
        assertThat(res.pdModelVersion()).isEqualTo(HeuristicDecisionScorer.PD_MODEL_VERSION);
        assertThat(res.decision()).isEqualTo("APPROVE");
        assertThat(res.score()).isGreaterThan(0.5);
        assertThat(res.pdScore()).isLessThan(0.30);
        assertThat(res.proba()).containsKeys("APPROVE", "CONDITIONAL", "REJECT");
        // proba 합계 ≈ 1.0
        double probaSum = res.proba().values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(probaSum).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("고위험 고객(연체 3건 + 높은 DSR + 낮은 신용점수) → REJECT, pdScore 높음")
    void score_highRiskApplicant_reject() {
        AutoReviewRequest req = highRiskRequest();

        AutoReviewResponse res = scorer.score(req);

        assertThat(res.decision()).isEqualTo("REJECT");
        assertThat(res.pdScore()).isGreaterThan(0.50);
    }

    @Test
    @DisplayName("decisionScore() = proba[APPROVE]")
    void score_decisionScore_equalsProbaApprove() {
        AutoReviewResponse res = scorer.score(primeRequest());

        assertThat(res.decisionScore()).isEqualTo(res.proba().get("APPROVE"));
    }

    // ------------------------------------------------------------------ //
    //  computeRisk() 개별 피처 기여                                         //
    // ------------------------------------------------------------------ //

    @ParameterizedTest(name = "creditScoreProxy={0} → risk 기여 ≈ {1}")
    @CsvSource({
        "800,  0.00",
        "700,  0.05",
        "600,  0.20",
        "400,  0.35",
        "200,  0.50",
    })
    @DisplayName("신용점수 구간별 리스크 기여 검증")
    void computeRisk_creditScore_ranges(int score, double expectedContrib) {
        AutoReviewRequest req = minimalRequest(score, null, null, 0, false, null);
        double r = scorer.computeRisk(req);
        assertThat(r).isCloseTo(expectedContrib, within(0.001));
    }

    @Test
    @DisplayName("DSR 0.70 초과 → 리스크 가산 0.35")
    void computeRisk_criticalDsr_adds035() {
        // creditScoreProxy=800(기여 0.0), dsr=0.75
        AutoReviewRequest req = minimalRequest(800, 0.75, null, 0, false, null);
        double r = scorer.computeRisk(req);
        assertThat(r).isCloseTo(0.35, within(0.001));
    }

    @Test
    @DisplayName("연체 3건 이상 → 리스크 가산 0.30")
    void computeRisk_delinquency3Plus_adds030() {
        AutoReviewRequest req = minimalRequest(800, null, null, 3, false, null);
        double r = scorer.computeRisk(req);
        assertThat(r).isCloseTo(0.30, within(0.001));
    }

    @Test
    @DisplayName("목적 위험 플래그 → 리스크 가산 0.12")
    void computeRisk_purposeRedFlag_adds012() {
        AutoReviewRequest req = minimalRequest(800, null, null, 0, true, null);
        double r = scorer.computeRisk(req);
        assertThat(r).isCloseTo(0.12, within(0.001));
    }

    @Test
    @DisplayName("bureauMaxStatus24m=3 이상 → 리스크 가산 0.18")
    void computeRisk_bureauMaxStatus3_adds018() {
        AutoReviewRequest req = minimalRequest(800, null, null, 0, false, 3);
        double r = scorer.computeRisk(req);
        assertThat(r).isCloseTo(0.18, within(0.001));
    }

    @Test
    @DisplayName("복합 리스크 합산이 1.0을 초과하지 않음")
    void computeRisk_never_exceeds_one() {
        AutoReviewRequest req = highRiskRequest();
        double r = scorer.computeRisk(req);
        assertThat(r).isLessThanOrEqualTo(1.0);
    }

    // ------------------------------------------------------------------ //
    //  toProba() proba 일관성                                              //
    // ------------------------------------------------------------------ //

    @ParameterizedTest(name = "r={0}")
    @CsvSource({"0.0", "0.10", "0.30", "0.50", "0.70", "0.90", "1.0"})
    @DisplayName("toProba — 어떤 r이든 확률 합계 ≈ 1.0, 모두 [0,1]")
    void toProba_sumsToOne(double r) {
        Map<String, Double> proba = HeuristicDecisionScorer.toProba(r);

        double sum = proba.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, within(0.001));
        proba.values().forEach(v -> {
            assertThat(v).isBetween(0.0, 1.0);
        });
    }

    @Test
    @DisplayName("r=0.0 → APPROVE 확률 최고")
    void toProba_rZero_approveHighest() {
        Map<String, Double> proba = HeuristicDecisionScorer.toProba(0.0);
        assertThat(proba.get("APPROVE")).isGreaterThan(proba.get("REJECT"));
    }

    @Test
    @DisplayName("r=1.0 → REJECT 확률 최고")
    void toProba_rOne_rejectHighest() {
        Map<String, Double> proba = HeuristicDecisionScorer.toProba(1.0);
        assertThat(proba.get("REJECT")).isGreaterThan(proba.get("APPROVE"));
    }

    // ------------------------------------------------------------------ //
    //  픽스처                                                              //
    // ------------------------------------------------------------------ //

    /** 우량 고객 요청 — creditScore 높음, DSR/LTV 낮음, 연체 없음 */
    private static AutoReviewRequest primeRequest() {
        return new AutoReviewRequest(
                1L, "남자", 35, "기혼", "해당없음", "부부", "자가",
                "대학교", "이공계", "사무직", "강남구", "서울", "PRIME",
                4, 60_000L, 200_000L, 30_000L, 20_000L, 10_000L,
                0.20, 0.40, 4_000L, 500L,
                0, 780,
                "HMDA_01", 30_000L, 120, "LIVING", false,
                "IT", 1, 1, 5, true, 0, 0
        );
    }

    /** 고위험 고객 — 신용 낮음 + DSR 높음 + 연체 다수 + 목적 플래그 */
    private static AutoReviewRequest highRiskRequest() {
        return new AutoReviewRequest(
                2L, "여자", 28, "미혼", "해당없음", "독신", "전세",
                "고등학교", null, "단순노무직", "노원구", "서울", "MASS",
                1, 18_000L, 5_000L, 20_000L, 0L, 20_000L,
                0.75, 0.90, 1_200L, 800L,
                4, 150,
                "HMDA_03", 50_000L, 60, "INVEST", true,
                null, 3, 2, 0, true, 5, 4
        );
    }

    /**
     * 단일 피처만 활성화한 최소 요청.
     *
     * <p>테스트 대상 외 피처는 리스크 기여 0이 되도록 안전값(non-null 0) 사용:
     * <ul>
     *   <li>dsr=null → 0.0 (임계값 미만, 기여 0)</li>
     *   <li>ltv=null → 0.0 (임계값 미만, 기여 0)</li>
     *   <li>bureauMax=null → 0 (기여 0)</li>
     * </ul>
     */
    private static AutoReviewRequest minimalRequest(
            Integer creditScore, Double dsr, Double ltv,
            Integer delinquency, boolean redFlag, Integer bureauMax
    ) {
        return new AutoReviewRequest(
                99L, null, 30, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                dsr != null ? dsr : 0.0,        // null → 안전값 0.0
                ltv != null ? ltv : 0.0,        // null → 안전값 0.0
                null, null,
                delinquency, creditScore,
                null, null, null, null, redFlag,
                null, null, null, null, null, null,
                bureauMax != null ? bureauMax : 0  // null → 안전값 0
        );
    }
}
